package org.skepsun.kototoro.video.domain

import eu.kanade.tachiyomi.animesource.model.Video
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudstreamPlaybackSelectionTest {

    @Test
    fun `zero and short playback durations require another mirror`() {
        assertTrue(isSuspiciousCloudstreamPlaybackDuration(0L))
        assertTrue(isSuspiciousCloudstreamPlaybackDuration(90_000L))
        assertFalse(isSuspiciousCloudstreamPlaybackDuration(90_001L))
        assertFalse(isSuspiciousCloudstreamPlaybackDuration(-1L))
    }

    @Test
    fun `short playback is only stalled when its position does not advance`() {
        assertTrue(isStalledCloudstreamPlayback(0L, 0L, 0L))
        assertTrue(isStalledCloudstreamPlayback(1_000L, 500L, 1_000L))
        assertFalse(isStalledCloudstreamPlayback(60_000L, 0L, 2_000L))
        assertFalse(isStalledCloudstreamPlayback(120_000L, 0L, 0L))
    }

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
