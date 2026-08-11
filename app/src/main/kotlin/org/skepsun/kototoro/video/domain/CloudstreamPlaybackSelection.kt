package org.skepsun.kototoro.video.domain

import com.lagradost.cloudstream3.utils.Qualities
import eu.kanade.tachiyomi.animesource.model.Video

private const val MAX_SUSPICIOUS_PLAYBACK_DURATION_MS = 90_000L
private const val MIN_HEALTHY_PLAYBACK_PROGRESS_MS = 2_000L

internal fun isRejectedCloudstreamProbe(contentType: String?, prefix: ByteArray): Boolean {
    if (contentType?.startsWith("image/", ignoreCase = true) == true) return true
    if (prefix.size >= 4 &&
        prefix[0] == 0x89.toByte() &&
        prefix[1] == 0x50.toByte() &&
        prefix[2] == 0x4E.toByte() &&
        prefix[3] == 0x47.toByte()
    ) {
        return true
    }
    if (prefix.size >= 3 &&
        prefix[0] == 0xFF.toByte() &&
        prefix[1] == 0xD8.toByte() &&
        prefix[2] == 0xFF.toByte()
    ) {
        return true
    }
    if (prefix.size >= 3 && prefix.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF") return true
    val textPrefix = prefix.toString(Charsets.UTF_8).trimStart().lowercase()
    return textPrefix.startsWith("<!doctype html") || textPrefix.startsWith("<html")
}

internal fun isRejectedCloudstreamSegmentProbe(
    contentType: String?,
    prefix: ByteArray,
    isDeclaredHls: Boolean,
): Boolean {
    return !isDeclaredHls && isRejectedCloudstreamProbe(contentType, prefix)
}

internal fun isSuspiciousCloudstreamPlaybackDuration(durationMs: Long): Boolean {
    return durationMs in 0L..MAX_SUSPICIOUS_PLAYBACK_DURATION_MS
}

internal fun isStalledCloudstreamPlayback(
    durationMs: Long,
    initialPositionMs: Long,
    currentPositionMs: Long,
): Boolean {
    return isSuspiciousCloudstreamPlaybackDuration(durationMs) &&
        currentPositionMs - initialPositionMs < MIN_HEALTHY_PLAYBACK_PROGRESS_MS
}

internal fun List<Video>.sortedCloudstreamVideos(): List<Video> {
    return distinctBy(Video::videoUrl).sortedByDescending { video ->
        Qualities.entries.minBy { quality ->
            kotlin.math.abs(quality.value - (video.resolution ?: Qualities.Unknown.value))
        }.defaultPriority
    }
}

internal fun List<Video>.resolveCloudstreamVideo(selection: Video): IndexedValue<Video>? {
    val index = indexOf(selection)
    return getOrNull(index)?.let { IndexedValue(index, it) }
}
