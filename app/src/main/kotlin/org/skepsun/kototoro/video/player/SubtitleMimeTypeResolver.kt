package org.skepsun.kototoro.video.player

import androidx.media3.common.MimeTypes

internal object SubtitleMimeTypeResolver {
    fun resolve(explicitMimeType: String?, url: String): String {
        explicitMimeType?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
