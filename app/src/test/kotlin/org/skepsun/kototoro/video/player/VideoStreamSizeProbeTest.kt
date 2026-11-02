package org.skepsun.kototoro.video.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoStreamSizeProbeTest {

    @Test
    fun `head content length is used without downloading the stream`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1048576"))
        server.start()
        try {
            val size = VideoStreamSizeProbe(OkHttpClient()).probe(
                url = server.url("/movie.mp4").toString(),
                headers = mapOf("Referer" to "https://example.test/"),
            )

            assertEquals(1048576L, size)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("HEAD", request.method)
            assertEquals("https://example.test/", request.headers["Referer"])
        } finally {
            server.close()
        }
    }

    @Test
    fun `ranged get recovers the total when head is rejected`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-0/104857600")
                .setBody("x"),
        )
        server.start()
        try {
            val size = VideoStreamSizeProbe(OkHttpClient()).probe(server.url("/movie.mp4").toString())

            assertEquals(104857600L, size)
            assertEquals(2, server.requestCount)
            assertEquals("HEAD", server.takeRequest().method)
            val ranged = server.takeRequest()
            assertEquals("GET", ranged.method)
            assertEquals("bytes=0-0", ranged.headers["Range"])
        } finally {
            server.close()
        }
    }

    @Test
    fun `server that ignores the range still reports the full length`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
        server.start()
        try {
            assertEquals(
                10L,
                VideoStreamSizeProbe(OkHttpClient()).probe(server.url("/movie.mp4").toString()),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `manifest urls are skipped instead of reporting the manifest size`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val probe = VideoStreamSizeProbe(OkHttpClient())
            assertNull(probe.probe(server.url("/playlist.m3u8").toString()))
            assertNull(probe.probe(server.url("/stream.mpd").toString()))
            assertNull(probe.probe("magnet:?xt=urn:btih:0123456789abcdef"))
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `adaptive content type is not reported as a file size`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U"),
        )
        server.start()
        try {
            assertNull(
                VideoStreamSizeProbe(OkHttpClient()).probe(server.url("/stream").toString()),
            )
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `missing resource and unreachable host stay unknown`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        val missingUrl = server.url("/movie.mp4").toString()
        val unreachableUrl = server.url("/offline.mp4").toString()
        try {
            assertNull(VideoStreamSizeProbe(OkHttpClient()).probe(missingUrl))
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
        assertNull(VideoStreamSizeProbe(OkHttpClient(), callTimeoutMs = 2_000L).probe(unreachableUrl))
    }

    @Test
    fun `content range helper ignores unusable values`() {
        assertEquals(104857600L, totalFromContentRange("bytes 0-0/104857600"))
        assertEquals(7L, totalFromContentRange("  BYTES 0-0/7 "))
        assertNull(totalFromContentRange("bytes 0-0/*"))
        assertNull(totalFromContentRange("items 0-0/7"))
        assertNull(totalFromContentRange(null))
    }

    @Test
    fun `manifest helpers match stream descriptors only`() {
        assertTrue(looksLikeAdaptiveManifest("https://cdn.test/a/index.m3u8?token=1"))
        assertTrue(looksLikeAdaptiveManifest("https://cdn.test/a/manifest.mpd#frag"))
        assertFalse(looksLikeAdaptiveManifest("https://cdn.test/a/movie.mp4"))
        assertTrue(isAdaptiveContentType("application/x-mpegURL"))
        assertTrue(isAdaptiveContentType("application/dash+xml"))
        assertFalse(isAdaptiveContentType("video/mp4"))
        assertFalse(isAdaptiveContentType(null))
    }
}
