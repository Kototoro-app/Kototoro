package org.skepsun.kototoro.ios.compat

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import eu.kanade.tachiyomi.createAppModule
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.skepsun.kototoro.core.source.RoutedSourceRuntime
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.parserhost.ParserPluginRegistry
import org.skepsun.kototoro.parserhost.ParserSourceRuntime
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import org.skepsun.kototoro.source.host.MihonPreferenceBridge
import org.skepsun.kototoro.source.host.SourceHostPlugin
import org.skepsun.kototoro.source.host.SourceHostPreferenceUiPlatform
import org.skepsun.kototoro.source.host.SourceHostRuntimePlatform
import org.skepsun.kototoro.source.host.SourceHostRuntimes
import org.skepsun.kototoro.source.host.SourceImageStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.KoinRegistrar
import java.io.File
import java.net.CookieHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * iOS platform for the pinned Mobile API: Mihon / Tsundoku / Aniyomi extensions plus kototoro / kotatsu / UMA parser
 * JARs on one network client. Kototoro's native adapter supplies WebKit, ImageIO, CoreText and JavaScriptCore.
 */
class MihonIosPlatform : SourceHostPreferenceUiPlatform, SourceHostRuntimePlatform {
    private var application: IosApplication? = null
    private var preferences: MihonPreferenceBridge? = null
    private var container: KoinApplication? = null
    private var client: OkHttpClient? = null
    private var thread: Thread? = null
    private val looper = AtomicReference<Looper>()
    private var previousInjekt: InjektScope? = null
    private var previousCookies: CookieHandler? = null
    private var ownedCookies: CookieHandler? = null
    private var ownsClaim = false
    private var closed = false
    /** Parser plugins' cached source lists, so start-up does not open plugins whose sources go unused. */
    private var parserCatalogs: Path? = null

    override fun initialize(): ClassLoader = error("Persistent preferences are required by the iOS platform")

    @Synchronized
    override fun initialize(preferences: SourcePreferenceStore): ClassLoader {
        check(!closed && application == null)
        synchronized(processOwner) {
            check(!claimed && GlobalContext.getOrNull() == null) { "The JVM already owns a compatibility platform" }
            claimed = true
            ownsClaim = true
        }
        val root = (preferences as? FileSourcePreferenceStore)?.directory?.parent?.resolve("compat")
            ?: error("File-backed preferences are required by the iOS platform")
        val ready = CountDownLatch(1)
        val startupFailure = AtomicReference<Throwable>()
        val main = Thread({
            try {
                Looper.prepare()
                val current = requireNotNull(Looper.myLooper())
                looper.set(current)
                Looper.setMainLooperForTest(current)
                ready.countDown()
                Looper.loop()
            } catch (error: Throwable) { startupFailure.set(error); ready.countDown() }
        }, "Kototoro iOS compatibility main").apply { isDaemon = true }
        thread = main
        main.start()
        check(ready.await(10, TimeUnit.SECONDS)) { "iOS compatibility Looper startup timed out" }
        startupFailure.get()?.let { throw IllegalStateException("iOS compatibility Looper failed", it) }
        val handler = Handler(requireNotNull(looper.get()))
        val bridge = MihonPreferenceBridge(javaClass.classLoader, preferences, dispatchListener = { action ->
            check(handler.post(action)) { "iOS compatibility callback was rejected" }
        }).also { this.preferences = it }
        val app = IosApplication(root, bridge).also { application = it }
        parserCatalogs = root.resolve("parser-catalogs")
        val koin = KoinApplication.init().also { container = it }
        koin.modules(createAppModule(app), module { single<Context> { app } })
        GlobalContext.startKoin(koin)
        previousInjekt = Injekt
        Injekt = InjektScope(KoinRegistrar())
        WebView.setProviderFactory { throw UnsupportedOperationException("WKWebView is not connected") }
        // Read before the store loads it: its own startup load loses cookies of dotted domains.
        val savedCookies = app.getSharedPreferences("cookie_store", 0).all
        app.onCreate()
        previousCookies = CookieHandler.getDefault()
        val helper = try { koin.koin.get<NetworkHelper>() } finally {
            ownedCookies = CookieHandler.getDefault().takeUnless { it === previousCookies }
        }
        restoreIosCookies(helper.cookieStore, savedCookies)
        // Kototoro's per-source domain setting reaches extensions whose address R8 folded into a constant.
        client = installDomainRewrite(helper)
        installWebView()
        return javaClass.classLoader
    }

    /**
     * Extensions' Android WebViews (and CookieManager) run on WKWebView once the app registered the native side
     * ([IosWebKit]); the cookie manager keeps the NetworkHelper's cookie handler as OkHttp's store, so this comes after
     * the client. Without the native side WebView stays unavailable, as before.
     */
    private fun installWebView() {
        if (IosWebKit.isAvailable) IosWebKit.install()
    }

    override fun preferenceContext(): Any = requireNotNull(application).also { check(!closed) }

    @Synchronized
    override fun openRuntimes(
        plugins: List<SourceHostPlugin>,
        images: SourceImageStore?,
        preferences: SourcePreferenceStore?,
    ): SourceHostRuntimes {
        check(!closed)
        val parsers = IosParserPlatform(
            requireNotNull(client) { "Initialize the platform before opening plugin runtimes" },
            requireNotNull(preferences) { "Parser plugins need persistent preferences" },
        )
        val registry = ParserPluginRegistry(parsers, catalogDirectory = parserCatalogs)
        try {
            for (plugin in plugins) {
                val start = System.nanoTime()
                registry.load(Path.of(plugin.path), plugin.id, plugin.sha256)
                System.err.println("[source-host] plugin ${plugin.id}: ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        } catch (failure: Throwable) {
            try { registry.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        val runtime = ParserSourceRuntime(registry, parsers, images,
            ebookAssets = (images as? org.skepsun.kototoro.source.host.FileSourceImageStore)?.directory)
        return SourceHostRuntimes(
            routes = listOf(RoutedSourceRuntime.Route(registry::owns, runtime)),
            titles = { registry.sources().associate { it.source.name to it.title } },
            sources = { registry.installed().associate { plugin -> plugin.id to plugin.sources.map { it.source } } },
            update = { next, publish -> updatePlugins(registry, next, publish) },
            owner = registry,
        )
    }

    /**
     * One install or uninstall at a time: the changed plugin is built and validated before [publish] persists the
     * record, then swapped in; the other loaded plugins are untouched. Anything larger reopens all runtimes.
     */
    private fun updatePlugins(registry: ParserPluginRegistry, next: List<SourceHostPlugin>, publish: () -> Unit): Boolean {
        val loaded = registry.installed().associateBy { it.id }
        val wanted = next.associateBy { it.id }
        val removed = loaded.keys - wanted.keys
        val changed = next.filter { loaded[it.id]?.metadata?.sha256 != it.sha256 }
        when {
            changed.size == 1 && removed.isEmpty() -> {
                val plugin = changed.single()
                val start = System.nanoTime()
                registry.replace(Path.of(plugin.path), plugin.id, plugin.sha256) { publish() }
                System.err.println("[source-host] plugin ${plugin.id} (incremental): ${(System.nanoTime() - start) / 1_000_000} ms")
            }
            changed.isEmpty() && removed.isNotEmpty() -> {
                publish()
                removed.forEach(registry::unload)
            }
            changed.isEmpty() -> publish()
            else -> return false
        }
        return true
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
        org.skepsun.kototoro.source.host.HostDomainRewrites.installed = false
        client?.let {
            release { it.dispatcher.cancelAll() }
            release { it.connectionPool.evictAll() }
            release { it.cache?.close() }
            release { it.dispatcher.executorService.shutdown() }
        }
        if (ownedCookies != null && CookieHandler.getDefault() === ownedCookies) {
            release { CookieHandler.setDefault(previousCookies) }
        }
        previousInjekt?.let { release { Injekt = it } }
        container?.let { owned ->
            release { if (GlobalContext.getOrNull() === owned.koin) GlobalContext.stopKoin() else owned.close() }
        }
        release { preferences?.close() }
        release { looper.get()?.quitSafely() }
        thread?.let { main ->
            if (Thread.currentThread() !== main) release { main.join(10000); check(!main.isAlive) }
        }
        if (looper.get() != null && Looper.getMainLooper() === looper.get()) {
            release { Looper.clearMainLooperForTest() }
        }
        application = null; preferences = null; client = null; container = null; thread = null
        if (ownsClaim) {
            synchronized(processOwner) {
                claimed = false
            }
            ownsClaim = false
        }
        failure?.let { throw it }
    }

    companion object {
        private val processOwner = Any()
        private var claimed = false
    }
}

private class IosApplication(private val root: Path, private val preferences: MihonPreferenceBridge) :
    eu.kanade.tachiyomi.App() {
    private val files = directory("files")
    private val cache = directory("cache")
    private val backup = directory("no-backup")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getSharedPreferences(name) as SharedPreferences
    override fun getApplicationContext(): Context = this
    override fun getClassLoader(): ClassLoader = javaClass.classLoader
    override fun getPackageName(): String = "org.skepsun.kototoro.ios"
    override fun getMainLooper(): Looper = requireNotNull(Looper.getMainLooper())
    override fun getDataDir(): File = root.toFile()
    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getCodeCacheDir(): File = cache
    override fun getNoBackupFilesDir(): File = backup
    override fun getSystemService(name: String): Any = when (name) {
        Context.ACTIVITY_SERVICE -> org.skepsun.kototoro.ios.shims.IosServices.activityManager()
        Context.DISPLAY_SERVICE -> org.skepsun.kototoro.ios.shims.IosServices.displayManager()
        else -> throw UnsupportedOperationException("iOS service: $name")
    }
    override fun getResources(): android.content.res.Resources = android.content.res.Resources.getSystem()
    override fun getPackageManager(): android.content.pm.PackageManager =
        throw UnsupportedOperationException("iOS packages")
    private fun directory(name: String): File = Files.createDirectories(root.resolve(name)).toFile()
}
