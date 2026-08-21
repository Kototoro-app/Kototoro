package org.skepsun.kototoro.core.parser.tvbox

import java.util.Locale

internal object TVBoxPlayback {

    private val directMediaMarkers = listOf(
        ".m3u8",
        ".mp4",
        ".flv",
        ".mpd",
        ".mkv",
        ".webm",
        ".avi",
        ".mov",
    )

    private val webPlaybackHosts = listOf(
        "iqiyi.com",
        "qq.com",
        "youku.com",
        "mgtv.com",
        "bilibili.com",
        "sohu.com",
        "pptv.com",
        "le.com",
        "1905.com",
    )

    private val embeddedMediaRegex = Regex(
        """https?:\\?/\\?/[^"'\\\s<>]+?\.(?:m3u8|mp4|flv|mpd|mkv|webm|avi|mov)[^"'\\\s<>]*""",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun normalizeLocator(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        val pipeNormalized = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("proxy://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true)
        ) {
            trimmed.substringBefore('|').trim()
        } else {
            trimmed
        }
        val markers = listOf(";md5;", ";pk;")
        markers.forEach { marker ->
            val index = pipeNormalized.indexOf(marker, ignoreCase = true)
            if (index >= 0) {
                return pipeNormalized.substring(0, index).trim()
            }
        }
        val separatorIndex = pipeNormalized.indexOf(';')
        return if (separatorIndex >= 0) {
            pipeNormalized.substring(0, separatorIndex).trim()
        } else {
            pipeNormalized
        }
    }

    fun resolveDetailItemId(explicitId: String?, requestedId: String): String? {
        return explicitId?.trim()?.takeIf { it.isNotBlank() }
            ?: requestedId.trim().takeIf { it.isNotBlank() }
    }

    fun resolvePlayerUrl(playerUrl: String?, episodeUrl: String): String? {
        playerUrl?.takeIf { it.isNotBlank() }?.let { return normalizeLocator(it) }
        return normalizeLocator(episodeUrl).takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    fun looksLikeDirectPlaybackUrl(value: String): Boolean {
        val normalized = normalizeLocator(value).lowercase(Locale.ROOT)
        return directMediaMarkers.any { normalized.contains(it) }
    }

    fun looksLikeHtmlPlaybackPage(value: String): Boolean {
        val normalized = normalizeLocator(value)
        val lower = normalized.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }
        if (looksLikeDirectPlaybackUrl(lower)) {
            return false
        }
        if (lower.contains(".html") || lower.contains(".shtml")) {
            return true
        }
        if (lower.contains("url=http") || lower.contains("url=https")) {
            return true
        }
        val host = runCatching { android.net.Uri.parse(normalized).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host.isNotBlank() && webPlaybackHosts.any { host == it || host.endsWith(".$it") }
    }

    fun extractEmbeddedMediaUrl(html: String): String? {
        val match = embeddedMediaRegex.find(html)?.value ?: return null
        return match
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }
}
