package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.parsers.model.ContentChapter

class CloudstreamPlaybackIsolationTest {

    @Test
    fun `switching anime starts the requested first episode and its subtitles`() = runTest {
        val api = PlaybackApi("Anime isolation")
        val source = CloudstreamSource(api, "test.cs3", "test.isolation")
        val catalog = CloudstreamCatalog(source, object : CloudstreamPlatform {})
        val first = animeChapters(api, source, "first")
        val second = animeChapters(api, source, "second")

        assertPlayback(catalog, first[0])
        assertPlayback(catalog, second[0])
        assertPlayback(catalog, second[1])
        assertPlayback(catalog, first[0])
        assertEquals(listOf(first[0].url, second[0].url, second[1].url), api.requests)
    }

    @Test
    fun `persisted chapters sharing an old id resolve their own links`() = runTest {
        val api = PlaybackApi("Legacy isolation")
        val source = CloudstreamSource(api, "test.cs3", "test.isolation")
        val first = animeChapters(api, source, "first")[0].copy(id = 1L)
        val second = animeChapters(api, source, "second")[0].copy(id = 1L)

        assertPlayback(CloudstreamCatalog(source, object : CloudstreamPlatform {}), first)
        assertPlayback(CloudstreamCatalog(source, object : CloudstreamPlatform {}), second)
        assertEquals(listOf(first.url, second.url), api.requests)
    }

    @Test
    fun `numbered chapter ids preserve the persisted anime and tv identities`() = runTest {
        val api = PlaybackApi("Chapter isolation")
        val source = CloudstreamSource(api, "test.cs3", "test.isolation")
        val first = animeChapters(api, source, "first")[0]
        val second = animeChapters(api, source, "second")[0]
        // Existing IDs are scoped to a title by storage; playback cache keys must supply their own isolation.
        assertEquals(429914384622516331L, first.id)
        assertEquals(429914384622516331L, second.id)
        val refreshed = api.newAnimeLoadResponse("Renamed", "https://example.test/renamed", TvType.Anime) {
            episodes[DubStatus.Subbed] = listOf(
                api.newEpisode("https://example.test/refreshed-locator") {
                    season = 1
                    episode = 1
                },
            )
        }
        assertEquals(first.id, mapCloudstreamChapters(refreshed, source).single().id)

        suspend fun tvChapter(title: String) = mapCloudstreamChapters(
            api.newTvSeriesLoadResponse(
                title, "https://example.test/$title", TvType.TvSeries,
                listOf(
                    api.newEpisode("https://example.test/$title/1") {
                        season = 1
                        episode = 1
                    },
                ),
            ),
            source,
        ).single()
        assertEquals(4933655571639901666L, tvChapter("first").id)
        assertEquals(4933655571639901666L, tvChapter("second").id)
    }

    @Test
    fun `unnumbered chapter ids preserve the persisted locator identity`() = runTest {
        val api = PlaybackApi("Chapter isolation")
        val source = CloudstreamSource(api, "test.cs3", "test.isolation")
        val response = api.newAnimeLoadResponse("Special", "https://example.test/anime", TvType.Anime) {
            episodes[DubStatus.Subbed] = listOf(api.newEpisode("https://example.test/special"))
        }

        assertEquals(5335887828024379882L, mapCloudstreamChapters(response, source).single().id)
    }

    @Test
    fun `refreshed locator resolves again without changing the chapter id`() = runTest {
        val api = PlaybackApi("Locator isolation")
        val source = CloudstreamSource(api, "test.cs3", "test.isolation")
        val catalog = CloudstreamCatalog(source, object : CloudstreamPlatform {})
        val chapter = animeChapters(api, source, "first")[0]
        val refreshed = chapter.copy(url = "https://example.test/refreshed")

        assertPlayback(catalog, chapter)
        assertPlayback(catalog, refreshed)
        assertEquals("${refreshed.url}/video.m3u8", catalog.getPages(refreshed).single().url)
        assertEquals(listOf(chapter.url, refreshed.url), api.requests)
    }

    private suspend fun assertPlayback(catalog: CloudstreamCatalog, chapter: ContentChapter) {
        val events = catalog.playbackEvents(chapter).toList()
        val page = events.filterIsInstance<CloudstreamPlaybackEvent.Link>().single().page
        assertEquals("${chapter.url}/video.m3u8", page.url)
        assertEquals(
            listOf("${chapter.url}/sub.vtt"),
            events.filterIsInstance<CloudstreamPlaybackEvent.Subtitle>().map { it.track.url },
        )
    }

    private suspend fun animeChapters(api: MainAPI, source: CloudstreamSource, title: String) = mapCloudstreamChapters(
        api.newAnimeLoadResponse(title, "https://example.test/$title", TvType.Anime) {
            episodes[DubStatus.Subbed] = (1..2).map { number ->
                api.newEpisode("https://example.test/$title/$number") {
                    season = 1
                    episode = number
                }
            }
        },
        source,
    )

    private class PlaybackApi(override var name: String) : MainAPI() {
        override var mainUrl = "https://example.test"
        val requests = mutableListOf<String>()

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            requests += data
            subtitleCallback(newSubtitleFile("English", "$data/sub.vtt"))
            callback(
                newExtractorLink(
                    source = name,
                    name = "Server",
                    url = "$data/video.m3u8",
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = mainUrl
                    quality = 1080
                },
            )
            return true
        }
    }
}
