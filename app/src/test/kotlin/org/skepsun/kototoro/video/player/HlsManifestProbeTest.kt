package org.skepsun.kototoro.video.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HlsManifestProbeTest {
	@Test
	fun `manifest signature is detected despite misleading content type`() = runTest {
		val server = MockWebServer()
		server.enqueue(
			MockResponse()
				.addHeader("Content-Type", "video/mp4")
				.setBody("\uFEFF  \n#EXTM3U\n#EXT-X-VERSION:3\n"),
		)
		server.start()
		try {
			val detected = HlsManifestProbe(OkHttpClient()).isHls(
				url = server.url("/stream?id=1").toString(),
				headers = mapOf("Referer" to "https://example.test/"),
			)

			assertTrue(detected)
			assertEquals("https://example.test/", server.takeRequest().headers["Referer"])
		} finally {
			server.close()
		}
	}

	@Test
	fun `content type alone does not misclassify html as hls`() = runTest {
		val server = MockWebServer()
		server.enqueue(
			MockResponse()
				.addHeader("Content-Type", "application/vnd.apple.mpegurl")
				.setBody("<html>challenge</html>"),
		)
		server.start()
		try {
			assertFalse(
				HlsManifestProbe(OkHttpClient()).isHls(
					url = server.url("/stream").toString(),
					headers = emptyMap(),
				),
			)
		} finally {
			server.close()
		}
	}

	@Test
	fun `plain prefix helper rejects non manifest content`() {
		assertTrue(looksLikeHlsManifest("#EXTM3U\nsegment.ts"))
		assertFalse(looksLikeHlsManifest("not a manifest"))
	}
}
