package org.skepsun.kototoro.video.player

import java.net.URI

internal data class HlsSegmentInfo(
    val url: String,
    val durationSec: Double,
    /** Declared by `EXT-X-BYTERANGE`; `null` when the segment is a standalone file. */
    val byteLength: Long?,
)

internal data class HlsMediaPlaylist(
    val segments: List<HlsSegmentInfo>,
    /** `false` for live/event playlists that have no `EXT-X-ENDLIST`, whose final size is unknown. */
    val isComplete: Boolean,
)

/** Picks the variant the downloader would pick: the one with the highest declared `BANDWIDTH`. */
internal fun selectHighestBandwidthVariant(baseUrl: String, master: String): String? {
    val lines = master.lineSequence().map { it.trim() }.toList()
    var bestUrl: String? = null
    var bestBandwidth = -1L
    for (i in lines.indices) {
        if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
        val bandwidth = lines[i].substringAfter("BANDWIDTH=", "").substringBefore(',').toLongOrNull() ?: 0L
        val next = lines.getOrNull(i + 1).orEmpty()
        if (next.isNotEmpty() && !next.startsWith("#") && bandwidth >= bestBandwidth) {
            bestBandwidth = bandwidth
            bestUrl = next
        }
    }
    return bestUrl?.let { resolveHlsUrl(baseUrl, it) }
}

internal fun parseHlsMediaPlaylist(baseUrl: String, text: String): HlsMediaPlaylist {
    val segments = ArrayList<HlsSegmentInfo>()
    var duration: Double? = null
    var byteLength: Long? = null
    var complete = false
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF:") ->
                duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
            line.startsWith("#EXT-X-BYTERANGE:") ->
                byteLength = line.removePrefix("#EXT-X-BYTERANGE:").substringBefore('@').trim().toLongOrNull()
            line == "#EXT-X-ENDLIST" -> complete = true
            line.isNotEmpty() && !line.startsWith("#") -> {
                duration?.takeIf { it > 0 }?.let { segments += HlsSegmentInfo(resolveHlsUrl(baseUrl, line), it, byteLength) }
                duration = null
                byteLength = null
            }
        }
    }
    return HlsMediaPlaylist(segments, complete)
}

/**
 * Estimates the downloaded size of a finished playlist.
 *
 * Playlists that declare every segment's byte range are summed exactly. Otherwise a few segments
 * (first, middle, near the end) are measured through [sampleLength] and their average bitrate is
 * extrapolated over the whole duration, so the result is only a rough figure.
 */
internal suspend fun estimateHlsBytes(
    playlist: HlsMediaPlaylist,
    sampleLength: suspend (HlsSegmentInfo) -> Long?,
): Long? {
    val segments = playlist.segments
    if (!playlist.isComplete || segments.isEmpty()) return null
    val ranged = segments.count { it.byteLength != null }
    if (ranged == segments.size) return segments.sumOf { it.byteLength ?: 0L }.takeIf { it > 0 }
    // A ranged segment reports its whole container file when measured, which would wildly overshoot.
    if (ranged > 0) return null
    var bytes = 0L
    var seconds = 0.0
    listOf(0, segments.size / 2, segments.size - 2)
        .map { it.coerceIn(0, segments.lastIndex) }
        .distinct()
        .forEach { index ->
            val length = sampleLength(segments[index]) ?: return@forEach
            bytes += length
            seconds += segments[index].durationSec
        }
    if (bytes <= 0 || seconds <= 0.0) return null
    return (bytes / seconds * segments.sumOf { it.durationSec }).toLong().takeIf { it > 0 }
}

private fun resolveHlsUrl(baseUrl: String, reference: String): String {
    return runCatching { URI(baseUrl).resolve(reference).toString() }.getOrDefault(reference)
}
