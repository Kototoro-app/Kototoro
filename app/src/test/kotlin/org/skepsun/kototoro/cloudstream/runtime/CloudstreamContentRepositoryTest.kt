package org.skepsun.kototoro.cloudstream.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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

}
