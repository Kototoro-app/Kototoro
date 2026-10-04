package org.skepsun.kototoro.desktop.compat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Owns a real SDK platform/WebView lifetime; every network request targets the loopback fixture. */
object DesktopWebViewProviderProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try { runBlocking {
            val root = Path.of(args[0])
            FileSourcePreferenceStore(root.resolve("preferences")).use { preferences ->
                val platform = MihonDesktopPlatform(root.resolve("compat"), File(args[1]))
                platform.initialize(preferences)
                var view: WebView? = null
                try {
                    val finished = Channel<String>(Channel.UNLIMITED)
                    val errors = Channel<String>(Channel.UNLIMITED)
                    val stoppedValue = CompletableDeferred<String>()
                    LocalBrowserSite().use { site ->
                        val webView = onMain {
                            WebView(platform.preferenceContext() as Context).also { browser ->
                                view = browser
                                browser.settings.javaScriptEnabled = true
                                browser.settings.userAgentString = "Kototoro-Provider-Fixture/1"
                                browser.webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                        checkMainThread()
                                        if (url == site.url("/slow")) {
                                            view.stopLoading()
                                            view.loadDataWithBaseURL(null, "<html><title>after stop</title></html>",
                                                "text/html", "utf-8", null)
                                            view.evaluateJavascript("document.title") {
                                                checkMainThread(); stoppedValue.complete(it)
                                            }
                                        }
                                    }
                                    override fun onPageFinished(view: WebView, url: String) {
                                        checkMainThread(); check(finished.trySend(url).isSuccess)
                                    }
                                    @Suppress("DEPRECATION")
                                    override fun onReceivedError(view: WebView, code: Int, description: String, url: String) {
                                        checkMainThread(); check(code == ERROR_UNKNOWN)
                                        check(description.isNotBlank()); check(errors.trySend(url).isSuccess)
                                    }
                                }
                                browser.webChromeClient = object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView, title: String) { checkMainThread() }
                                }
                            }
                        }
                        val firstTitle = CompletableDeferred<String>()
                        onMain {
                            CookieManager.getInstance().setCookie(site.url("/"), "provider_sdk=kept; Path=/; HttpOnly")
                            webView.loadUrl(site.url("/headers"), mapOf("X-Source-Provider" to "preserved"))
                            webView.evaluateJavascript("document.title") { checkMainThread(); firstTitle.complete(it) }
                        }
                        check(withTimeout(10000) { firstTitle.await() } == "\"loopback\"")
                        check(withTimeout(10000) { finished.receive() } == site.url("/headers"))
                        val get = site.requests.poll(3, TimeUnit.SECONDS) ?: error("Missing provider GET")
                        check(get.headers["X-source-provider"]?.single() == "preserved")
                        check(get.headers["User-agent"]?.single() == "Kototoro-Provider-Fixture/1")
                        check(get.headers["Cookie"].orEmpty().joinToString().contains("provider_sdk=kept"))

                        val base = site.url("/base/document?fixture=1#anchor")
                        onMain {
                            webView.loadDataWithBaseURL(base,
                                "<html><title>base origin</title><script>document.cookie='provider_browser=kept;path=/';</script></html>",
                                "text/html", "utf-8", null)
                        }
                        check(withTimeout(10000) { finished.receive() } == base)
                        val location = evaluate(webView, "location.href")
                        check(location == "\"$base\"")
                        check(onMain { webView.url } == base)
                        check(onMain { webView.title } == "base origin")
                        check(CookieManager.getInstance().getCookie(site.url("/")).contains("provider_browser=kept"))
                        check(site.requests.isEmpty()) { "Injected document must not be fetched" }

                        val body = byteArrayOf(0, -1, 13, 10) + "中文=原样".toByteArray()
                        onMain { webView.postUrl(site.url("/post"), body) }
                        check(withTimeout(10000) { finished.receive() } == site.url("/post"))
                        val post = site.requests.poll(3, TimeUnit.SECONDS) ?: error("Missing provider POST")
                        check(post.method == "POST" && post.body.contentEquals(body))
                        check(post.headers["Content-type"]?.single() == "application/x-www-form-urlencoded")
                        onMain { webView.reload() }
                        check(withTimeout(10000) { finished.receive() } == site.url("/post"))
                        val reloaded = site.requests.poll(3, TimeUnit.SECONDS) ?: error("Missing reload POST")
                        check(reloaded.method == "POST" && reloaded.body.contentEquals(body))

                        onMain { webView.loadUrl(site.url("/invalid"), mapOf("X-Invalid" to "bad\r\nInjected: header")) }
                        check(withTimeout(10000) { errors.receive() } == site.url("/invalid"))
                        check(evaluate(webView, "6 * 7") == "42")

                        onMain { webView.loadUrl(site.url("/slow")) }
                        check(withTimeout(5000) { stoppedValue.await() } == "\"after stop\"")
                        check(withTimeout(5000) { finished.receive() } == "about:blank")
                        check(errors.tryReceive().isFailure)
                        val helper = GlobalContext.get().get<NetworkHelper>()
                        check(helper.cookieStore.getStoredCookies().any { it.name == "provider_browser" })
                    }
                } finally {
                    view?.let { onMain { it.destroy() } }
                    platform.close()
                }
            }
            check(Looper.getMainLooper() == null)
            awaitBrowserProfileRelease(root)
            println("WEBVIEW_PROVIDER_OK")
        } } catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
    }

    private fun checkMainThread() = check(Looper.myLooper() === Looper.getMainLooper())

    private suspend fun evaluate(view: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        onMain { view.evaluateJavascript(script) { checkMainThread(); result.complete(it) } }
        return withTimeout(10000) { result.await() }
    }

    private suspend fun <T> onMain(action: () -> T): T {
        val result = CompletableDeferred<T>()
        check(Handler(requireNotNull(Looper.getMainLooper())).post {
            try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) }
        })
        return withTimeout(15000) { result.await() }
    }
}
