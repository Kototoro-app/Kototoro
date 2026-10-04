package org.skepsun.kototoro.source.host

import java.io.Closeable
import org.skepsun.kototoro.core.source.SourcePreferenceStore

/** Desktop embedding owns compatibility initialization; the session closes it after all extension loaders. */
interface SourceHostPlatform : Closeable {
    fun initialize(): ClassLoader
}

/** Optional SPI. Bind Application.getSharedPreferences before returning, before any extension constructor runs. */
interface SourceHostPreferencePlatform : SourceHostPlatform {
    fun initialize(preferences: SourcePreferenceStore): ClassLoader
}

/** Optional settings capability: returns a Context from the already initialized parent AndroidCompat runtime. */
interface SourceHostPreferenceUiPlatform : SourceHostPreferencePlatform {
    fun preferenceContext(): Any
}
