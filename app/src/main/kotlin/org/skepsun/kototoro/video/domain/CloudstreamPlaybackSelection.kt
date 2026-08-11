package org.skepsun.kototoro.video.domain

import com.lagradost.cloudstream3.utils.Qualities
import eu.kanade.tachiyomi.animesource.model.Video

private const val MAX_SUSPICIOUS_PLAYBACK_DURATION_MS = 90_000L
private const val MIN_HEALTHY_PLAYBACK_PROGRESS_MS = 2_000L

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
