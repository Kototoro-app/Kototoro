package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTorrentLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource

class CloudstreamContentRepositoryTest {

	@Test
	fun `blank or structured episode names use a readable fallback`() {
		assertEquals("Episode 6", resolveCloudstreamEpisodeTitle(null, 6))
		assertEquals("Episode 6", resolveCloudstreamEpisodeTitle("  ", 6))
		assertEquals("Episode 6", resolveCloudstreamEpisodeTitle("[{\"fileUrl\":\"https://video.test/a.m3u8\"}]", 6))
		assertEquals("Finale", resolveCloudstreamEpisodeTitle(" Finale ", 6))
	}

	@Test
	fun `structured plugin locator is not a direct media URL`() {
		assertEquals(true, isCloudstreamStructuredLocator("[{\"fileUrl\":\"https://video.test/a.m3u8\"}]"))
		assertEquals(true, isCloudstreamStructuredLocator("  {\"url\":\"https://video.test/a.mp4\"}"))
		assertEquals(false, isCloudstreamStructuredLocator("https://video.test/a.m3u8"))
	}

	@Test
	fun `missing plugin urls reject stringified null values`() {
		assertTrue("".isMissingCloudstreamUrl())
		assertTrue(" null ".isMissingCloudstreamUrl())
		assertTrue("UNDEFINED".isMissingCloudstreamUrl())
		assertFalse("https://video.test/live.m3u8".isMissingCloudstreamUrl())
	}

	@Test
	fun `metadata round trip preserves rich details and ignores future fields`() {
		val encoded = CloudstreamMetadataCodec.encodeContent(
			CloudstreamContentMetadata(
				type = TvType.Anime.name,
				year = 2025,
				durationMinutes = 24,
				mainPageRequestName = "Trending",
				mainPageRequestData = "trending",
				homeRowName = "Popular this week",
				horizontalImages = true,
				actors = listOf(CloudstreamActorMetadata(name = "Actor")),
				seasons = listOf(CloudstreamSeasonMetadata(season = 7, displaySeason = 2)),
			),
		)
		val decoded = CloudstreamMetadataCodec.decodeContent(encoded.dropLast(1) + ",\"future\":true}")

		assertEquals(TvType.Anime.name, decoded?.type)
		assertEquals(2025, decoded?.year)
		assertEquals("Trending", decoded?.mainPageRequestName)
		assertEquals("trending", decoded?.mainPageRequestData)
		assertEquals("Popular this week", decoded?.homeRowName)
		assertEquals(true, decoded?.horizontalImages)
		assertEquals("Actor", decoded?.actors?.single()?.name)
		assertEquals(2, decoded?.seasons?.single()?.displaySeason)
	}

	@Test
	fun `details metadata preserves list context and existing artwork headers`() = runTest {
		val previous = CloudstreamMetadataCodec.encodeContent(
			CloudstreamContentMetadata(
				posterHeaders = mapOf("Referer" to "https://example.test/"),
				quality = "HD",
				providerId = 42,
				mainPageRequestName = "Trending",
				mainPageRequestData = "trending",
				homeRowName = "Popular this week",
				horizontalImages = true,
			),
		)
		val response = testApi.newMovieLoadResponse("Movie", "/movie", TvType.Movie, "movie-data")

		val decoded = CloudstreamMetadataCodec.decodeContent(
			response.toContentMetadata(previous, testSource.name),
		)

		assertEquals(mapOf("Referer" to "https://example.test/"), decoded?.posterHeaders)
		assertEquals("HD", decoded?.quality)
		assertEquals(42, decoded?.providerId)
		assertEquals("Trending", decoded?.mainPageRequestName)
		assertEquals("trending", decoded?.mainPageRequestData)
		assertEquals("Popular this week", decoded?.homeRowName)
		assertEquals(true, decoded?.horizontalImages)
	}

	@Test
	fun `sensitive artwork headers stay runtime only`() {
		val headers = mapOf(
			"Referer" to "https://example.test/",
			"Cookie" to "session=secret",
			"AUTHORIZATION" to "Bearer secret",
		)
		val persisted = CloudstreamArtworkHeaders.persistable(headers)

		assertEquals(mapOf("Referer" to "https://example.test/"), persisted)
		CloudstreamArtworkHeaders.remember("source", "https://img.test/poster.jpg", headers)
		assertEquals(
			headers,
			CloudstreamArtworkHeaders.resolve("source", "https://img.test/poster.jpg", persisted),
		)
	}

	@Test
	fun `single item responses use native playback locators`() = runTest {
		val movie = testApi.newMovieLoadResponse("Movie", "/movie", TvType.Movie, "movie-data")
		val live = testApi.newLiveStreamLoadResponse("Live", "/live", "live-data")
		val torrent = testApi.newTorrentLoadResponse(
			name = "Torrent",
			url = "/torrent",
			magnet = "magnet-data",
			torrent = "torrent-data",
		)
		val magnetFallback = testApi.newTorrentLoadResponse(
			name = "Magnet",
			url = "/magnet",
			magnet = "magnet-fallback",
			torrent = "",
		)

		assertEquals("movie-data", mapCloudstreamChapters(movie, testSource).single().url)
		assertEquals("live-data", mapCloudstreamChapters(live, testSource).single().url)
		assertEquals("torrent-data", mapCloudstreamChapters(torrent, testSource).single().url)
		assertEquals("magnet-fallback", mapCloudstreamChapters(magnetFallback, testSource).single().url)
		assertEquals(
			ExtractorLinkType.TORRENT.name,
			CloudstreamMetadataCodec.decodeEpisode(mapCloudstreamChapters(torrent, testSource).single().sourceData)?.linkType,
		)
		assertEquals(
			ExtractorLinkType.MAGNET.name,
			CloudstreamMetadataCodec.decodeEpisode(mapCloudstreamChapters(magnetFallback, testSource).single().sourceData)?.linkType,
		)
		assertTrue(mapCloudstreamChapters(testApi.newLiveStreamLoadResponse("Soon", "/soon", ""), testSource).isEmpty())
	}

	@Test
	fun `tv episodes sort by season and preserve unknown season`() = runTest {
		val response = testApi.newTvSeriesLoadResponse(
			name = "Series",
			url = "/series",
			type = TvType.TvSeries,
			episodes = listOf(
				testApi.newEpisode("season-two") {
					season = 2
					episode = 1
				},
				testApi.newEpisode("unknown") { name = "Special" },
				testApi.newEpisode("season-one") {
					season = 1
					episode = 2
				},
				testApi.newEpisode("") {
					season = 3
					episode = 1
				},
			),
		)

		val chapters = mapCloudstreamChapters(response, testSource)

		assertEquals(
			listOf(
				"https://example.test/unknown",
				"https://example.test/season-one",
				"https://example.test/season-two",
			),
			chapters.map { it.url },
		)
		assertEquals(0, chapters.first().volume)
		assertEquals("Special", chapters.first().title)
	}

	@Test
	fun `anime numbering is independent per dub group and duplicate episodes collapse`() = runTest {
		val response = testApi.newAnimeLoadResponse("Anime", "/anime", TvType.Anime) {
			episodes[DubStatus.Subbed] = listOf(
				testApi.newEpisode("sub-one") {
					season = 1
					episode = 1
				},
				testApi.newEpisode("sub-duplicate") {
					season = 1
					episode = 1
				},
				testApi.newEpisode("sub-missing"),
			)
			episodes[DubStatus.Dubbed] = listOf(testApi.newEpisode("dub-missing"))
		}

		val chapters = mapCloudstreamChapters(response, testSource)

		assertEquals(3, chapters.size)
		assertEquals(listOf(1f, 3f), chapters.filter { it.branch == DubStatus.Subbed.name }.map { it.number })
		assertEquals(1f, chapters.single { it.branch == DubStatus.Dubbed.name }.number)
		assertFalse(chapters.any { it.url == "sub-duplicate" })
		assertNull(CloudstreamMetadataCodec.decodeEpisode(chapters.first().sourceData)?.displaySeason)
	}

	@Test
	fun `stable identifiers do not depend on page position`() {
		val first = cloudstreamStableId("${testSource.name}|content|https://example.test/item")
		val second = cloudstreamStableId("${testSource.name}|content|https://example.test/item")

		assertEquals(first, second)
		assertTrue(first >= 0)
	}

	private companion object {
		val testApi = object : MainAPI() {
			override var name = "Test"
			override var mainUrl = "https://example.test"
			override var lang = "en"
			override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
		}
		val testSource = CloudstreamSource(testApi, "test.cs3", "test.plugin")
	}

}
