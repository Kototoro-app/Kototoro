package org.skepsun.kototoro.video.domain

import eu.kanade.tachiyomi.animesource.model.Video
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CloudstreamPlaybackSelectionTest {

    @Test
    fun `links use cloudstream quality priority and deduplicate urls`() {
        val videos = listOf(
            Video(videoUrl = "360", resolution = 360),
            Video(videoUrl = "unknown", resolution = null),
            Video(videoUrl = "1080", resolution = 1080),
            Video(videoUrl = "1080", resolution = 720),
        )

        assertEquals(
            listOf("1080", "unknown", "360"),
            videos.sortedCloudstreamVideos().map(Video::videoUrl),
        )
    }

    @Test
    fun `stale selection is rejected after links are replaced`() {
        val stale = Video(videoUrl = "same", videoTitle = "old chapter")
        val current = listOf(Video(videoUrl = "same", videoTitle = "new chapter"))

        assertNull(current.resolveCloudstreamVideo(stale))
        assertEquals(0, current.resolveCloudstreamVideo(current.single())?.index)
    }
}
