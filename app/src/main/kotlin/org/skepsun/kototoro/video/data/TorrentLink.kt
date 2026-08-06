package org.skepsun.kototoro.video.data

import java.net.URI

internal fun String.isTorrentLocator(): Boolean {
    val locator = trim()
    if (locator.startsWith("magnet:", ignoreCase = true)) return true
    val path = runCatching { URI(locator).path }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: locator.substringBefore('?').substringBefore('#')
    return path.endsWith(".torrent", ignoreCase = true)
}

internal fun torrentFileIndex(locator: String): Int {
    val query = runCatching { URI(locator).rawQuery }.getOrNull()
        ?: locator.substringAfter('?', missingDelimiterValue = "").takeIf(String::isNotBlank)
        ?: return 0
    return query.split('&')
        .firstNotNullOfOrNull { parameter ->
            val (name, value) = parameter.split('=', limit = 2).let {
                it.first() to it.getOrElse(1) { "" }
            }
            value.toIntOrNull()?.takeIf { name.equals("index", ignoreCase = true) && it >= 0 }
        }
        ?: 0
}

internal fun selectTorrentFileIndex(requestedIndex: Int, availableIndices: List<Int>): Int =
    requestedIndex.takeIf(availableIndices::contains) ?: availableIndices.firstOrNull() ?: 0

internal fun parseTorrentHttpRange(value: String, totalBytes: Long): LongRange? {
    if (totalBytes <= 0 || !value.startsWith("bytes=", ignoreCase = true)) return null
    val range = value.substringAfter('=').trim()
    if (range.isEmpty() || ',' in range) return null
    val (startValue, endValue) = range.split('-', limit = 2).takeIf { it.size == 2 } ?: return null
    if (startValue.isEmpty()) {
        val suffixLength = endValue.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (totalBytes - suffixLength).coerceAtLeast(0)..<totalBytes
    }
    val start = startValue.toLongOrNull()?.takeIf { it in 0 until totalBytes } ?: return null
    val end = endValue.toLongOrNull()?.coerceAtMost(totalBytes - 1) ?: (totalBytes - 1)
    return (start..end).takeIf { end >= start }
}
