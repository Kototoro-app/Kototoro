package org.skepsun.kototoro.core.util

import android.os.Build
import android.webkit.MimeTypeMap
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.parsers.util.removeSuffix
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import java.nio.file.Files
import coil3.util.MimeTypeMap as CoilMimeTypeMap

object MimeTypes {

    // 常见图片格式的回退映射，确保即使系统不支持也能识别
    private val FALLBACK_MIME_TYPES = mapOf(
        "webp" to "image/webp",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "jxl" to "image/jxl",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
    )

    fun getMimeTypeFromExtension(fileName: String): MimeType? {
        val ext = getNormalizedExtension(fileName) ?: return null
        // 优先使用 Coil 的映射
        val fromCoil = CoilMimeTypeMap.getMimeTypeFromExtension(ext)?.toMimeTypeOrNull()
        if (fromCoil != null) return fromCoil
        // 回退到内置映射（主要为 webp 等格式提供支持）
        return FALLBACK_MIME_TYPES[ext]?.toMimeTypeOrNull()
    }

    fun getMimeTypeFromUrl(url: String): MimeType? {
        return CoilMimeTypeMap.getMimeTypeFromUrl(url)?.toMimeTypeOrNull()
    }

    fun getExtension(mimeType: MimeType?): String? {
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType?.toString() ?: return null)?.nullIfEmpty()
    }

    @Blocking
    fun probeMimeType(file: File): MimeType? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatchingCancellable {
                Files.probeContentType(file.toPath())?.toMimeTypeOrNull()
            }.getOrNull()?.let { return it }
        }
        return getMimeTypeFromExtension(file.name)
    }

    fun getNormalizedExtension(name: String): String? = name
        .lowercase()
        .removeSuffix('~')
        .removeSuffix(".tmp")
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 }
}
