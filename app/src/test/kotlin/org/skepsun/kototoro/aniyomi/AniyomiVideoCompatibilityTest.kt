package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AniyomiVideoCompatibilityTest {

    @Test
    fun `legacy video API remains the preferred path`() = runTest {
        val source = FakeAnimeSource(
            legacyVideos = listOf(Video(videoUrl = "https://example.org/legacy.m3u8")),
            hosters = listOf(Hoster(videoList = listOf(Video(videoUrl = "https://example.org/new.m3u8")))),
        )

        val result = source.getCompatibleVideoList(SEpisode.create())

        assertEquals(listOf("https://example.org/legacy.m3u8"), result.map { it.videoUrl })
        assertEquals(0, source.hosterRequests)
    }

    @Test
    fun `empty legacy result falls back to embedded hoster videos`() = runTest {
        val source = FakeAnimeSource(
            hosters = listOf(
                Hoster(videoList = listOf(Video(videoUrl = "https://example.org/embedded.m3u8"))),
            ),
        )

        val result = source.getCompatibleVideoList(SEpisode.create())

        assertEquals(listOf("https://example.org/embedded.m3u8"), result.map { it.videoUrl })
        assertEquals(0, source.hosterVideoRequests)
    }

    @Test
    fun `hoster without embedded videos is resolved through hoster API`() = runTest {
        val source = FakeAnimeSource(
            hosters = listOf(Hoster(hosterUrl = "https://example.org/hoster")),
            hosterVideos = listOf(Video(videoUrl = "https://example.org/resolved.m3u8")),
        )

        val result = source.getCompatibleVideoList(SEpisode.create())

        assertEquals(listOf("https://example.org/resolved.m3u8"), result.map { it.videoUrl })
        assertEquals(1, source.hosterVideoRequests)
    }

    @Test
    fun `legacy failure is propagated when hoster fallback is unavailable`() = runTest {
        val error = try {
            FakeAnimeSource(legacyFailure = IllegalStateException("legacy failed"))
                .getCompatibleVideoList(SEpisode.create())
            null
        } catch (e: Throwable) {
            e
        }

        assertInstanceOf(IllegalStateException::class.java, error)
        assertEquals("legacy failed", error?.message)
    }

    private class FakeAnimeSource(
        private val legacyVideos: List<Video> = emptyList(),
        private val hosters: List<Hoster> = emptyList(),
        private val hosterVideos: List<Video> = emptyList(),
        private val legacyFailure: Throwable? = null,
    ) : AnimeSource {
        override val id: Long = 1L
        override val name: String = "Fake"

        var hosterRequests = 0
        var hosterVideoRequests = 0

        override suspend fun getVideoList(episode: SEpisode): List<Video> {
            legacyFailure?.let { throw it }
            return legacyVideos
        }

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
            hosterRequests++
            return hosters
        }

        override suspend fun getVideoList(hoster: Hoster): List<Video> {
            hosterVideoRequests++
            return hosterVideos
        }
    }
}
