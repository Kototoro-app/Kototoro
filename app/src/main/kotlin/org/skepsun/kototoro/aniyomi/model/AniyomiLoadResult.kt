package org.skepsun.kototoro.aniyomi.model

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource

/**
 * Result of loading an Aniyomi extension.
 */
sealed class AniyomiLoadResult {

    /**
     * Successfully loaded extension.
     */
    data class Success(
        val pkgName: String,
        val appName: String,
        val versionCode: Long,
        val versionName: String,
        val libVersion: Double,
        val lang: String,
        val isNsfw: Boolean,
        val sources: List<AnimeSource>,
        val isManagedLocal: Boolean = false,
    ) : AniyomiLoadResult() {

        /**
         * Get only AnimeCatalogueSource instances (sources that support browsing).
         */
        val catalogueSources: List<AnimeCatalogueSource>
            get() = sources.filterIsInstance<AnimeCatalogueSource>()
    }

    /**
     * Failed to load extension.
     */
    data class Error(
        val pkgName: String,
        val message: String,
        val exception: Throwable? = null,
    ) : AniyomiLoadResult()

    /**
     * Extension is untrusted (signature not verified).
     */
    data class Untrusted(
        val pkgName: String,
        val appName: String,
        val versionCode: Long,
        val versionName: String,
    ) : AniyomiLoadResult()
}

/**
 * Extension metadata extracted from APK.
 */
data class AniyomiExtensionInfo(
    val pkgName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String,
    val libVersion: Double,
    val lang: String,
    val isNsfw: Boolean,
    val sourceClassName: String,
    val apkPath: String,
)
