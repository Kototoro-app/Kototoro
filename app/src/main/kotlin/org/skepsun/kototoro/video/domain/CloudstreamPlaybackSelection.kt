package org.skepsun.kototoro.video.domain

import com.lagradost.cloudstream3.utils.Qualities
import eu.kanade.tachiyomi.animesource.model.Video

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
