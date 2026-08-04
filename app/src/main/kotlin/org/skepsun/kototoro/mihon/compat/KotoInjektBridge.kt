package org.skepsun.kototoro.mihon.compat

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
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
    private val webViewExecutor: WebViewExecutor? = null,
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
            val networkHelper = KotoNetworkHelper(httpClient, cookieJar, webViewExecutor)
            android.util.Log.d(
                "KotoInjektBridge",
                "Creating KotoNetworkHelper with webViewExecutorPresent=${webViewExecutor != null}",
            )
            
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    // Application and Context
                    addSingleton(application)
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
