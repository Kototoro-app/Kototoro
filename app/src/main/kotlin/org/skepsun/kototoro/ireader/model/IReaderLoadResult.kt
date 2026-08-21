package org.skepsun.kototoro.ireader.model

import org.skepsun.kototoro.ireader.model.IReaderMangaSource

sealed class IReaderLoadResult {
    data class Success(
        val pkgName: String,
        val appName: String,
        val versionCode: Long,
        val versionName: String,
        val libVersion: Double,
        val isNsfw: Boolean,
        val sources: List<ireader.core.source.Source>,
        val isManagedLocal: Boolean = false,
    ) : IReaderLoadResult()

    data class Error(
        val pkgName: String,
        val message: String,
        val exception: Throwable? = null,
    ) : IReaderLoadResult()
}
