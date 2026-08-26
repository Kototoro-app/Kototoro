package org.skepsun.kototoro.mihon.compat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Resources
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.UserAgentProvider
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.network.webview.CloudflareSolveCoordinator
import org.skepsun.kototoro.core.network.webview.WebViewClearanceSolver
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.extensions.runtime.tachiyomi.remapTachiyomiPreferenceKey
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import javax.inject.Singleton

@Singleton
class KotoInjektBridge(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val cookieJar: okhttp3.CookieJar,
    private val webViewExecutor: dagger.Lazy<WebViewExecutor>? = null,
    private val settings: AppSettings? = null,
    private val clearanceSolver: WebViewClearanceSolver? = null,
    private val solveCoordinator: CloudflareSolveCoordinator? = null,
) {

    private val application: Application
        get() = context.applicationContext as Application

    @Volatile
    private var initialized = false

    /**
     * Initialize Injekt with Kototoro's dependencies.
     * This must be called before loading any Mihon extensions.
     *
     * Thread-safe - can be called multiple times.
     */
    @Synchronized
    fun initialize() {
        if (initialized) return

        try {
            val networkHelper = KotoNetworkHelper(
                baseClient = httpClient,
                cookieJar = cookieJar,
                defaultUserAgent = UserAgentProvider.get(context),
                webViewExecutor = webViewExecutor,
                settings = settings,
                clearanceSolver = clearanceSolver,
                solveCoordinator = solveCoordinator,
            )

            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    // Application and Context. The Application singleton is a delegating wrapper
                    // that remaps SharedPreferences names for Tsundoku sources (T3B.5); the
                    // explicit Application typing keeps the Injekt registration keyed to the
                    // `Application` type so `Injekt.get<Application>()` still resolves.
                    val applicationProxy: Application = NamespacedApplication(application)
                    addSingleton(applicationProxy)
                    addSingletonFactory<Context> { context.applicationContext }

                    // Network components
                    addSingletonFactory<NetworkHelper> { networkHelper }
                    addSingletonFactory<OkHttpClient> { httpClient }
                    addSingletonFactory<okhttp3.CookieJar> { cookieJar }

                    // Json - explicitly type it to ensure Injekt matches correctly
                    val json = Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    }
                    addSingletonFactory<Json> { json }
                    addSingletonFactory<StringFormat> { json }
                    addSingletonFactory<SerialFormat> { json }
                    addSingletonFactory<ProtoBuf> { ProtoBuf }
                }
            })

            initialized = true
            android.util.Log.d("KotoInjektBridge", "Injekt initialized with Kototoro dependencies")
        } catch (e: Throwable) {
            android.util.Log.e("KotoInjektBridge", "CRITICAL: Failed to initialize Injekt bridge", e)
            // Do not rethrow, so the app can continue to function without Mihon
        }
    }

    /**
     * Check if Injekt has been initialized.
     */
    fun isInitialized(): Boolean = initialized
}

/**
 * Delegating [Application] handed to Tachiyomi-ABI extension code through the Injekt registry.
 *
 * Extension code resolves source preferences with
 * `Injekt.get<Application>().getSharedPreferences("source_${id}", MODE_PRIVATE)`, and all Mihon /
 * Aniyomi / Tsundoku ecosystems share that single registry — hence a shared `source_<id>`
 * namespace per numeric id, which lets a Tsundoku extension and a Mihon extension with the same
 * id contaminate each other's preferences (T3B.5). This wrapper forwards every call to the real
 * [real] application except `getSharedPreferences`, which remaps the preference name through
 * [remapTachiyomiPreferenceKey] based on [MihonRequestContext.currentSource] before delegating.
 *
 * Why a plain subclass instead of `java.lang.reflect.Proxy`? `Proxy.newProxyInstance` only
 * accepts *interfaces* and `Application` is a concrete class, so a dynamic proxy would require a
 * bytecode-generation dependency (ByteBuddy/CGLIB) — out of scope (no new deps). And, contrary to
 * the usual assumption, `ContextWrapper.getSharedPreferences(String, int)` is **not** `final` in
 * the compileSdk 37 API surface, so overriding exactly that one method is legal. This wrapper is
 * not attached by the system, so it attaches [real] as its base context during construction. This
 * keeps every inherited Context API safe, including APIs such as `getDeviceId()` that newer Android
 * versions may call while constructing a WebView.
 *
 * This wrapper is only ever handed out to extension code through Injekt; the system never
 * instantiates it. Non-TSUNDOKU sources are unaffected because the remap is a no-op for them.
 */
internal class NamespacedApplication(
    private val real: Application,
) : Application() {

    init {
        attachBaseContext(real)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val remapped = remapTachiyomiPreferenceKey(name, MihonRequestContext.currentSource())
        return real.getSharedPreferences(remapped, mode)
    }

    override fun getApplicationContext(): Context = real.applicationContext

    override fun getBaseContext(): Context = real

    override fun getAssets(): AssetManager = real.assets

    override fun getResources(): Resources = real.resources
}
