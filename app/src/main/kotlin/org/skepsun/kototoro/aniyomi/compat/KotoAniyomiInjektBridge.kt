package org.skepsun.kototoro.aniyomi.compat

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import org.skepsun.kototoro.mihon.compat.KotoInjektBridge
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.StringFormat
import kotlinx.serialization.SerialFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge for Aniyomi's Injekt-based dependency injection.
 *
 * Reuses the initialization logic from KotoInjektBridge to ensure consistency.
 */
@Singleton
class KotoAniyomiInjektBridge @Inject constructor(
    private val mihonInjektBridge: KotoInjektBridge,
) {

    /**
     * Initialize Injekt with Kototoro's dependencies.
     * Delegates to Mihon's bridge as they share the same Injekt instance.
     */
    fun initialize() {
        mihonInjektBridge.initialize()
    }

    /**
     * Check if Injekt has been initialized.
     */
    fun isInitialized(): Boolean = mihonInjektBridge.isInitialized()
}
