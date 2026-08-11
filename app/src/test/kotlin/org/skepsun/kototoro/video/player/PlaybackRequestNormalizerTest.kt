package org.skepsun.kototoro.video.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackRequestNormalizerTest {
	@Test
	fun `standard hls is direct and filters unsafe headers`() {
		val result = PlaybackRequestNormalizer.normalize(
			url = "https://cdn.example/video/master.m3u8?token=abc",
			originalHeaders = mapOf(
				"Referer" to "https://example/",
				"Cookie" to "session=1",
				"Host" to "wrong.example",
				"Connection" to "close",
				"Content-Length" to "20",
			),
		)
		assertEquals(PlaybackMediaKind.HLS, result.mediaKind)
		assertEquals(PlaybackRoute.DIRECT, result.route)
		assertEquals(setOf("Referer", "Cookie"), result.headers.keys)
	}

	@Test
	fun `declared cloudstream json hls uses transforming proxy`() {
		val result = PlaybackRequestNormalizer.normalize(
			url = "https://cdn.example/token/index.json",
			declaredKind = PlaybackMediaKind.HLS,
			isCloudstream = true,
		)
		assertEquals(PlaybackRoute.TRANSFORMING_HLS_PROXY, result.route)
	}

	@Test
	fun `aniyomi nonstandard url stays direct without declared hls`() {
		val result = PlaybackRequestNormalizer.normalize(
			url = "https://cdn.example/watch/123",
		)
		assertEquals(PlaybackMediaKind.AUTO, result.mediaKind)
		assertEquals(PlaybackRoute.DIRECT, result.route)
	}

	@Test
	fun `dash and torrent are recognized`() {
		assertEquals(
			PlaybackMediaKind.DASH,
			PlaybackRequestNormalizer.normalize("https://cdn.example/master.mpd").mediaKind,
		)
		assertEquals(
			PlaybackRoute.TORRENT_LOCAL_HTTP,
			PlaybackRequestNormalizer.normalize("magnet:?xt=urn:btih:test").route,
		)
	}
}
