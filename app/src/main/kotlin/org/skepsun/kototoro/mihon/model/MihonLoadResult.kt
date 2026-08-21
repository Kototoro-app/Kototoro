package org.skepsun.kototoro.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source

/**
 * Result of loading a Mihon extension.
 */
sealed class MihonLoadResult {

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
        val sources: List<Source>,
        val isManagedLocal: Boolean = false,
    ) : MihonLoadResult() {

        /**
         * Get only CatalogueSource instances (sources that support browsing).
         */
        val catalogueSources: List<CatalogueSource>
            get() = sources.filterIsInstance<CatalogueSource>()
    }

    /**
     * Failed to load extension.
     */
    data class Error(
        val pkgName: String,
        val message: String,
        val exception: Throwable? = null,
    ) : MihonLoadResult()

    /**
     * Extension is untrusted (signature not verified).
     */
    data class Untrusted(
        val pkgName: String,
        val appName: String,
        val versionCode: Long,
        val versionName: String,
    ) : MihonLoadResult()
}

/**
 * Extension metadata extracted from APK.
 */
data class MihonExtensionInfo(
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
