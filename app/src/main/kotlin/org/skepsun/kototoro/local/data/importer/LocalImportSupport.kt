package org.skepsun.kototoro.local.data.importer

import org.skepsun.kototoro.local.data.hasZipExtension
import java.util.Locale

enum class LocalImportKind {
    MANGA,
    NOVEL,
    VIDEO,
}

internal object LocalImportSupport {
    const val IMPORT_STAGING_PREFIX = ".kototoro_import_"

    private val videoExtensions = setOf("mp4", "mkv", "ts", "webm", "avi", "m3u8")
    private val novelExtensions = setOf("epub", "txt")

    fun supportsFileName(fileName: String): Boolean {
        return hasZipExtension(fileName) || classifyFileName(fileName) != LocalImportKind.MANGA
    }

    fun classifyFileName(fileName: String): LocalImportKind {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension in videoExtensions -> LocalImportKind.VIDEO
            extension in novelExtensions -> LocalImportKind.NOVEL
            else -> LocalImportKind.MANGA
        }
    }

    fun contentFolderName(fileName: String): String {
        return fileName.substringBeforeLast('.').ifBlank { fileName }
    }
}
