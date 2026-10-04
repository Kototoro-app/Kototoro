package org.skepsun.kototoro.desktop.compat

import android.webkit.CookieManager
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.koin.core.context.GlobalContext
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

object DesktopBrowserCookieProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try { runBlocking {
            val root = Path.of(args[1])
            FileSourcePreferenceStore(root.resolve("preferences")).use { preferences ->
                val platform = MihonDesktopPlatform(root.resolve("compat"))
                platform.initialize(preferences)
                try {
                    val helper = GlobalContext.get().get<NetworkHelper>()
                    DesktopWebViewBridge(File(args[2]), root.resolve("browser")).use { browser ->
                        browser.start()
                        val cookies = platform.cookieBridge(browser)
                        val protectedUrl = "https://cookie-scope.invalid/other/page".toHttpUrl()
                        if (args[0] == "empty") {
                            check(helper.cookieStore.getStoredCookies().isEmpty())
                            cookies.push()
                            val remaining = browser.getCookies("")
                            check(remaining.isEmpty()) { "Native cookie identities after SDK clear: " + remaining.map { Triple(it.name, it.domain, it.path) } }
                        } else if (args[0] == "read" || args[0] == "clear" || args[0] == "sdk-clear") {
                            val disk = (platform.preferenceContext() as Context).getSharedPreferences("cookie_store", 0)
                            check(disk.getStringSet(protectedUrl.host, emptySet()).any { it.startsWith("survivor=") }) {
                                "SDK cookie namespace did not persist the token"
                            }
                            println("COOKIE_DOMAINS_ON_DISK=" + disk.all.keys.sorted())
                            val restored = helper.cookieStore.getStoredCookies().singleOrNull { it.name == "survivor" }
                                ?: error("SDK persistent secure cookie missing on reopening")
                            check(restored.httpOnly && restored.secure && restored.hostOnly && restored.path == "/other")
                            check(restored.persistent && restored.expiresAt > System.currentTimeMillis())
                            val domain = helper.cookieStore.getStoredCookies().single { it.name == "domain_survivor" }
                            check(!domain.hostOnly && domain.secure && domain.path == "/other")
                            check(helper.cookieStore.getStoredCookies().none { it.name == "session_survivor" })
                            cookies.push()
                            check(browser.getCookies(protectedUrl.toString()).singleOrNull { it.name == "survivor" }?.isHttpOnly == true) {
                                "Native cookie missing after restoring SDK"
                            }
                            if (args[0] == "clear") {
                                CookieManager.getInstance().removeAllCookies(null)
                                check(helper.cookieStore.getStoredCookies().isEmpty()) { "SDK clear did not update its own store" }
                                cookies.push()
                                val remaining = browser.getCookies("")
                                check(remaining.isEmpty()) { "Native cookie identities after clear: " + remaining.map { Triple(it.name, it.domain, it.path) } }
                            }
                            if (args[0] == "sdk-clear") CookieManager.getInstance().removeAllCookies(null)
                        } else {
                            val expiry = System.currentTimeMillis() + 60 * 60 * 1000
                            val survivor = Cookie.Builder().name("survivor").value("opaque_token").hostOnlyDomain(protectedUrl.host)
                                .path("/other").httpOnly().secure().expiresAt(expiry).build()
                            helper.cookieStore.addAll(protectedUrl, listOf(survivor))
                            LocalBrowserSite().use { site ->
                                val url = site.url("/cookies")
                                CookieManager.getInstance().setCookie(url, "sdk_value=from_sdk; Path=/; HttpOnly")
                                cookies.push()
                                val native = browser.getCookies(protectedUrl.toString()).singleOrNull { it.name == "survivor" }
                                    ?: error("Secure cookie missing in native jar after push; all names=" + browser.getCookies("").map { it.name })
                                check(native.isSecure && native.isHttpOnly && !native.isSession && native.path == "/other")
                                check(native.domain == protectedUrl.host)
                                check(native.expiresEpochMillis == expiry)
                                browser.navigate(url)
                                val request = site.requests.poll(3, java.util.concurrent.TimeUnit.SECONDS) ?: error("No browser request")
                                check(request.headers["Cookie"].orEmpty().joinToString().contains("sdk_value=from_sdk"))
                                browser.evaluateJs("document.cookie='browser_value=from_browser;path=/'; 'done'")
                                cookies.pull()
                                check(CookieManager.getInstance().getCookie(url).contains("browser_value=from_browser"))
                                helper.client.newCall(Request.Builder().url(site.url("/client")).build()).execute().use {
                                    check(it.isSuccessful)
                                }
                                val client = site.requests.poll(3, java.util.concurrent.TimeUnit.SECONDS) ?: error("No client request")
                                check(client.headers["Cookie"].orEmpty().joinToString().contains("browser_value=from_browser"))
                                browser.evaluateJs("document.cookie='browser_value=from_async;path=/'")
                                cookies.push()
                                cookies.pull()
                                check(CookieManager.getInstance().getCookie(url).contains("browser_value=from_async"))
                                CookieManager.getInstance().setCookie(url, "browser_value=from_sdk_update; Path=/")
                                cookies.push()
                                check(browser.evaluateJs("document.cookie").contains("browser_value=from_sdk_update"))
                                browser.deleteCookie("browser_value", "127.0.0.1", "/")
                                cookies.pull()
                                check(helper.cookieStore.getStoredCookies().none { it.name == "browser_value" })
                                CookieManager.getInstance().removeAllCookies(null)
                                cookies.push()
                                check(browser.getCookies("").isEmpty())
                            }
                            helper.cookieStore.addAll(protectedUrl, listOf(survivor))
                            val domain = Cookie.Builder().name("domain_survivor").value("domain_token")
                                .domain(protectedUrl.host).path("/other").secure().expiresAt(expiry).build()
                            val session = Cookie.Builder().name("session_survivor").value("session_token")
                                .hostOnlyDomain(protectedUrl.host).path("/").build()
                            helper.cookieStore.addAll(protectedUrl, listOf(domain, session))
                            cookies.push()
                            cookies.pull()
                            check(helper.cookieStore.getStoredCookies().any { it.name == "survivor" && it.persistent })
                            val disk = (platform.preferenceContext() as Context).getSharedPreferences("cookie_store", 0)
                            check(disk.getStringSet(protectedUrl.host, emptySet()).any { it.startsWith("survivor=") })
                        }
                    }
                } finally { platform.close() }
            }
            awaitBrowserProfileRelease(root)
            println("COOKIE_BRIDGE_OK=${args[0]}")
        } } catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
    }
}
