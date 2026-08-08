package org.skepsun.kototoro.core.parser.tvbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TVBoxPlaybackTest {

	@Test
	fun `detail item id falls back to requested id`() {
		assertEquals(
			"https://example.test/detail/1.html",
			TVBoxPlayback.resolveDetailItemId(null, "https://example.test/detail/1.html"),
		)
	}

	@Test
	fun `explicit detail item id takes precedence`() {
		assertEquals("vod-1", TVBoxPlayback.resolveDetailItemId("vod-1", "requested-1"))
	}

	@Test
	fun `spider player result takes precedence over http episode url`() {
		assertEquals(
			"https://media.example.test/video.m3u8",
			TVBoxPlayback.resolvePlayerUrl(
				playerUrl = "https://media.example.test/video.m3u8",
				episodeUrl = "https://example.test/detail/1.html",
			),
		)
	}

	@Test
	fun `episode url is retained when spider player result is empty`() {
		assertEquals(
			"https://media.example.test/video.mp4",
			TVBoxPlayback.resolvePlayerUrl(null, "https://media.example.test/video.mp4"),
		)
	}

	@Test
	fun `opaque episode id is rejected when spider player result is empty`() {
		assertEquals(null, TVBoxPlayback.resolvePlayerUrl(null, "139pan@opaque-id"))
	}
}
