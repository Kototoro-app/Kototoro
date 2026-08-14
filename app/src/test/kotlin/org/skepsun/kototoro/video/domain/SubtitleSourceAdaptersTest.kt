package org.skepsun.kototoro.video.domain

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentExternalTrack

class SubtitleSourceAdaptersTest {
    @Test
    fun `aniyomi subtitles inherit resolved video headers`() {
        val headers = mapOf("Referer" to "https://source.example/", "Cookie" to "session=1")
        val video = Video(
            videoUrl = "https://cdn.example/video.m3u8",
            subtitleTracks = listOf(Track("https://cdn.example/subtitle.vtt", "en")),
        )

        val subtitle = video.toPlaybackSubtitles(SubtitleOrigin.ANIYOMI_EXTERNAL, headers).single()

        assertEquals(SubtitleOrigin.ANIYOMI_EXTERNAL, subtitle.origin)
        assertEquals(headers, subtitle.headers)
        assertEquals("en", subtitle.languageTag)
    }

    @Test
    fun `cloudstream subtitles retain their independent headers`() {
        val source = ContentExternalTrack(
            url = "https://subtitle.example/file",
            lang = "English",
            headers = mapOf("Authorization" to "Bearer subtitle-token"),
        )

        val subtitle = source.toCloudstreamPlaybackSubtitle()

        assertEquals(SubtitleOrigin.CLOUDSTREAM_EXTERNAL, subtitle.origin)
        assertEquals(source.headers, subtitle.headers)
    }

    @Test
    fun `stable id does not depend on label`() {
        val first = Track("https://cdn.example/subtitle.srt", "English")
            .toPlaybackSubtitle(SubtitleOrigin.ANIYOMI_EXTERNAL)
        val renamed = Track("https://cdn.example/subtitle.srt", "en")
            .toPlaybackSubtitle(SubtitleOrigin.ANIYOMI_EXTERNAL)

        assertEquals(first.id, renamed.id)
    }
}
