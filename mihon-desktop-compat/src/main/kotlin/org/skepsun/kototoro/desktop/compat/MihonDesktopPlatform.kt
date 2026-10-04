package org.skepsun.kototoro.desktop.compat

import android.webkit.WebView
import eu.kanade.tachiyomi.createAppModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.PersistentCookieStore
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import org.skepsun.kototoro.source.host.MihonPreferenceBridge
import org.skepsun.kototoro.source.host.SourceHostPreferenceUiPlatform
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.KoinRegistrar
import java.net.CookieHandler
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

import java.io.File
import kotlinx.coroutines.runBlocking

/** Optional adapter for supplied pinned APIs. One platform lifetime per JVM; extensions reload within its registry. */
class MihonDesktopPlatform(
    private val dataDirectory: Path? = null,
    private val bridgeExecutable: File? = null,
    private val enableBrowserChallenges: Boolean = false,
) : SourceHostPreferenceUiPlatform {
    private var application: DesktopCompatibilityApplication? = null
    private var preferences: MihonPreferenceBridge? = null
    private var main: DesktopCompatibilityLooper? = null
    private var container: KoinApplication? = null
    private var client: OkHttpClient? = null
    private var cookies: PersistentCookieStore? = null
    private var previousInjekt: InjektScope? = null
    private var previousCookies: CookieHandler? = null
    private var ownedCookies: CookieHandler? = null
    private var activeBridges: MutableList<DesktopWebViewBridge>? = null
    private var closed = false
    private var browserFactoryOwned = false
    var browserChallenges: DesktopBrowserChallenges? = null
        private set

    override fun initialize(): ClassLoader = error("Persistent preferences must be supplied before source construction")

    @Synchronized
    override fun initialize(preferences: SourcePreferenceStore): ClassLoader {
        check(!closed && application == null) { "Desktop platform is already initialized or closed" }
        synchronized(processOwner) {
            check(!claimed) { "This JVM has already hosted a desktop compatibility platform" }
            check(GlobalContext.getOrNull() == null) { "The global Koin context belongs to another application" }
            claimed = true
        }
        try {
            val root = dataDirectory
                ?: (preferences as? FileSourcePreferenceStore)?.directory?.parent?.resolve("compat")
                ?: error("A desktop data directory is required for this preference backend")
            val absolute = root.toAbsolutePath().normalize()
            Files.createDirectories(absolute)
            require(Files.isDirectory(absolute, NOFOLLOW_LINKS)) { "Compatibility data must be a real directory" }
            val loop = DesktopCompatibilityLooper().also { main = it }
            val bridge = MihonPreferenceBridge(javaClass.classLoader, preferences, dispatchListener = loop::dispatch)
                .also { this.preferences = it }
            val app = DesktopCompatibilityApplication(absolute.toRealPath(), bridge).also { application = it }
            val koin = KoinApplication.init().also { container = it }
            koin.modules(createAppModule(app), module { single<android.content.Context> { app } })
            GlobalContext.startKoin(koin)
            previousInjekt = Injekt
            Injekt = InjektScope(KoinRegistrar())
            val targetBridge = bridgeExecutable ?: defaultBridgeExecutable()
            if (targetBridge != null && targetBridge.isFile) {
                val bridges = java.util.concurrent.CopyOnWriteArrayList<DesktopWebViewBridge>().also { activeBridges = it }
                WebView.setProviderFactory { webView ->
                    synchronized(this@MihonDesktopPlatform) {
                        check(!closed) { "Desktop platform is closed" }
                        val profile = absolute.resolve("webview2")
                        val wvBridge = DesktopWebViewBridge(targetBridge, userDataDir = profile)
                        bridges.add(wvBridge)
                        try {
                            runBlocking { wvBridge.start() }
                            DesktopWebViewProvider(webView, wvBridge, loop, cookieBridge(wvBridge))
                        } catch (error: Throwable) {
                            bridges.remove(wvBridge)
                            try { wvBridge.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                            throw error
                        }
                    }
                }
            } else {
                disableBrowser()
            }
            browserFactoryOwned = true
            val savedCookies = app.getSharedPreferences("cookie_store", 0).all
            app.onCreate()
            previousCookies = CookieHandler.getDefault()
            val helper = try { koin.koin.get<NetworkHelper>() } finally {
                ownedCookies = CookieHandler.getDefault().takeUnless { it === previousCookies }
            }
            cookies = helper.cookieStore
            restoreDesktopCookies(helper.cookieStore, savedCookies)
            // Force owned client creation so shutdown can close known dispatcher/cache resources.
            client = helper.client
            if (enableBrowserChallenges && targetBridge != null && targetBridge.isFile) {
                val challenges = DesktopBrowserChallenges(targetBridge, absolute.resolve("browser-challenges"), ::cookieBridge)
                    .also { browserChallenges = it }
                client = installDesktopChallengeClient(helper, challenges)
            } else if (enableBrowserChallenges) {
                // No browser component: Cloudflare refusals become plain errors instead of reaching the server-only handler.
                client = installDesktopChallengeClient(helper, null)
            }
            return javaClass.classLoader
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    @Synchronized
    override fun preferenceContext(): Any = requireNotNull(application).also { check(!closed) }

    /**
     * The platform's own HTTP client, with the shared cookie store and web-challenge handling. Other source ecosystems
     * derive their clients from it (`newBuilder()`), so a clearance obtained for one is visible to all.
     */
    @Synchronized
    fun sharedHttpClient(): OkHttpClient {
        check(!closed) { "Desktop platform is closed" }
        return requireNotNull(client) { "Desktop platform is not initialized" }
    }

    @Synchronized
    fun cookieBridge(browser: DesktopWebViewBridge): DesktopBrowserCookies {
        check(!closed) { "Desktop platform is closed" }
        return DesktopBrowserCookies(requireNotNull(cookies) { "Network cookies are not initialized" }, browser)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        if (browserFactoryOwned) {
            release { disableBrowser() }
            browserFactoryOwned = false
        }
        release { browserChallenges?.close() }
        browserChallenges = null
        client?.let { owned ->
            release { owned.dispatcher.cancelAll() }
            release { owned.connectionPool.evictAll() }
            release { owned.cache?.close() }
            release { owned.dispatcher.executorService.shutdown() }
        }
        if (ownedCookies != null && CookieHandler.getDefault() === ownedCookies) {
            release { CookieHandler.setDefault(previousCookies) }
        }
        previousInjekt?.let { release { Injekt = it } }
        container?.let { owned ->
            release {
                if (GlobalContext.getOrNull() === owned.koin) GlobalContext.stopKoin() else owned.close()
            }
        }
        activeBridges?.forEach { bridge ->
            release { bridge.close() }
        }
        activeBridges = null
        release { preferences?.close() }
        release { main?.close() }
        application = null; preferences = null; main = null; container = null; client = null
        previousInjekt = null; previousCookies = null; ownedCookies = null
        cookies = null
        failure?.let { throw it }
    }

    companion object {
        private val processOwner = Any()
        private var claimed = false
        private fun disableBrowser() {
            WebView.setProviderFactory { throw UnsupportedOperationException("Windows browser bridge is unavailable") }
        }

        fun defaultBridgeExecutable(): File? {
            val prop = System.getProperty("kototoro.compat.bridge.exe")
            if (!prop.isNullOrBlank()) {
                val f = File(prop)
                return f.takeIf { it.isFile }
            }
            return System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }
                ?.let { File(it, "browser/kototoro-webview-bridge.exe") }?.takeIf { it.isFile }
        }
    }
}
