package org.skepsun.kototoro.tsundoku

import eu.kanade.tachiyomi.source.Source
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiSourceRejection

/**
 * Result of loading one Tsundoku extension package (manager-facing; mirrors MihonLoadResult.
 * The upstream sources are plain ABI [Source] instances; wrapping into Kototoro
 * [org.skepsun.kototoro.parsers.model.ContentSource] adapters happens in the manager.
 */
sealed class TsundokuLoadResult {

    /** Successfully loaded extension (novel sources only, per-source rejections preserved). */
    data class Success(
        val pkgName: String,
        val appName: String,
        val versionCode: Long,
        val versionName: String,
        val libVersion: Double,
        val lang: String,
        val isNsfw: Boolean,
        val sources: List<Source>,
        val rejections: List<TachiyomiSourceRejection> = emptyList(),
        /** True when the APK lives in the app-private managed-local store (sideload). */
        val isManagedLocal: Boolean = false,
    ) : TsundokuLoadResult()

    /** Failed to load the extension, with the structured phase that failed. */
    data class Error(
        val pkgName: String,
        val phase: String,
        val message: String,
        val exception: Throwable? = null,
    ) : TsundokuLoadResult()
}

/** Extension metadata extracted from a Tsundoku APK (no class loading). */
data class TsundokuExtensionInfo(
    val pkgName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String,
    val libVersion: Double,
    val lang: String,
    val isNsfw: Boolean,
    val sourceClassName: String,
    val apkPath: String?,
)
