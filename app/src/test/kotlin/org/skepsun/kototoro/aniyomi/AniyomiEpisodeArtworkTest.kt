package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.aniyomi.model.toAniyomiEpisode
import org.skepsun.kototoro.aniyomi.model.toKotoChapter
import org.skepsun.kototoro.core.source.ChapterArtworkCodec

class AniyomiEpisodeArtworkTest {
    private val source = AniyomiAnimeSource(mockk<AnimeHttpSource> {
        every { id } returns 777L
        every { baseUrl } returns "https://anime.test"
    }, "fixture")

    @Test
    fun `relative episode previews survive conversion and playback reconstruction`() {
        val episode = episode("/preview.jpg")
        val chapter = episode.toKotoChapter(source)

        assertEquals("https://anime.test/preview.jpg", ChapterArtworkCodec.decode(chapter.sourceData)?.posterUrl)
        assertEquals("https://anime.test/preview.jpg", chapter.toAniyomiEpisode().preview_url)
        assertEquals(episode.url, chapter.toAniyomiEpisode().url)
    }

    @Test
    fun `an extension without previews keeps the same chapter identity and plain card`() {
        val withArtwork = episode("https://cdn.test/preview.jpg").toKotoChapter(source)
        val withoutArtwork = episode(null).toKotoChapter(source)

        assertEquals(withArtwork.id, withoutArtwork.id)
        assertEquals("https://cdn.test/preview.jpg", ChapterArtworkCodec.decode(withArtwork.sourceData)?.posterUrl)
        assertNull(withoutArtwork.sourceData)
        assertNull(withoutArtwork.toAniyomiEpisode().preview_url)
    }

    private fun episode(preview: String?) = SEpisode.create().apply {
        url = "/episode/1"
        name = "First"
        episode_number = 1f
        preview_url = preview
    }
}
