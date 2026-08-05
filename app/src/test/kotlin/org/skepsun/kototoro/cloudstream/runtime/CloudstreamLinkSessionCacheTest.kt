package org.skepsun.kototoro.cloudstream.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudstreamLinkSessionCacheTest {

	@Test
	fun `finished session replays cached values and is saturated`() {
		var now = 1_000L
		val cache = CloudstreamLinkSessionCache<String, String, String>(20_000L) { now }

		assertTrue(cache.addLink("episode", "video-url", "video"))
		assertTrue(cache.addSubtitle("episode", "subtitle-url", "subtitle"))
		assertFalse(cache.addLink("episode", "video-url", "duplicate"))
		cache.finish("episode")

		val snapshot = cache.prepare("episode", clearCache = false)
		assertEquals(listOf("video"), snapshot.links)
		assertEquals(listOf("subtitle"), snapshot.subtitles)
		assertTrue(snapshot.saturated)
	}

	@Test
	fun `expired and explicitly cleared sessions load again`() {
		var now = 1_000L
		val cache = CloudstreamLinkSessionCache<String, String, String>(20_000L) { now }

		cache.addLink("episode", "video-url", "video")
		cache.finish("episode")
		now += 20_001L

		val expired = cache.prepare("episode", clearCache = false)
		assertTrue(expired.links.isEmpty())
		assertFalse(expired.saturated)

		cache.addLink("episode", "new-url", "new-video")
		cache.finish("episode")
		val cleared = cache.prepare("episode", clearCache = true)
		assertTrue(cleared.links.isEmpty())
		assertFalse(cleared.saturated)
	}
}
