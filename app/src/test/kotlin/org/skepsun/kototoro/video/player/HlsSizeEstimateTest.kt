package org.skepsun.kototoro.video.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HlsSizeEstimateTest {

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        seg0.ts
        #EXTINF:10.0,
        sub/seg1.ts
        #EXTINF:5.0,
        https://cdn.test/seg2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `segments are parsed with resolved urls and durations`() {
        val playlist = parseHlsMediaPlaylist("https://host.test/a/index.m3u8", media)

        assertTrue(playlist.isComplete)
        assertEquals(
            listOf("https://host.test/a/seg0.ts", "https://host.test/a/sub/seg1.ts", "https://cdn.test/seg2.ts"),
            playlist.segments.map { it.url },
        )
        assertEquals(listOf(10.0, 10.0, 5.0), playlist.segments.map { it.durationSec })
    }

    @Test
    fun `the highest bandwidth variant is chosen from a master playlist`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=842x480
            mid/index.m3u8
        """.trimIndent()

        assertEquals(
            "https://host.test/v/high/index.m3u8",
            selectHighestBandwidthVariant("https://host.test/v/master.m3u8", master),
        )
    }

    @Test
    fun `sampled bitrate is extrapolated over the whole duration`() = runTest {
        val playlist = parseHlsMediaPlaylist("https://host.test/index.m3u8", media)

        // 1 MB per 10 s segment sampled, so 0.1 MB/s over 25 s.
        val estimate = estimateHlsBytes(playlist) { segment ->
            (segment.durationSec * 100_000).toLong()
        }

        assertEquals(2_500_000L, estimate)
    }

    @Test
    fun `declared byte ranges are summed exactly without sampling`() = runTest {
        val ranged = """
            #EXTM3U
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:1000@0
            all.mp4
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:2000@1000
            all.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val estimate = estimateHlsBytes(parseHlsMediaPlaylist("https://host.test/i.m3u8", ranged)) {
            error("must not be sampled")
        }

        assertEquals(3000L, estimate)
    }

    @Test
    fun `live playlists and unmeasurable segments give no estimate`() = runTest {
        val live = parseHlsMediaPlaylist("https://host.test/i.m3u8", media.replace("#EXT-X-ENDLIST", ""))
        assertFalse(live.isComplete)
        assertNull(estimateHlsBytes(live) { 1L })

        val finished = parseHlsMediaPlaylist("https://host.test/i.m3u8", media)
        assertNull(estimateHlsBytes(finished) { null })
    }

    @Test
    fun `measure estimates a finished hls playlist and flags it`() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/index.m3u8" -> MockResponse()
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody(
                        "#EXTM3U\n#EXTINF:10.0,\nseg0.ts\n#EXTINF:10.0,\nseg1.ts\n#EXT-X-ENDLIST\n",
                    )
                request.path?.endsWith(".ts") == true -> MockResponse().setHeader("Content-Length", "500000")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val size = VideoStreamSizeProbe(OkHttpClient()).measure(server.url("/index.m3u8").toString())

            assertEquals(MeasuredVideoSize(bytes = 1_000_000L, isEstimated = true), size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `measure reads a long playlist to its end marker`() = runTest {
        val body = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            repeat(1000) { append("#EXTINF:3,\n%07d.ts\n".format(it)) }
            append("#EXT-X-ENDLIST\n")
        }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/index.m3u8" -> MockResponse().setBody(body)
                request.path?.endsWith(".ts") == true -> MockResponse().setHeader("Content-Length", "300000")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val size = VideoStreamSizeProbe(OkHttpClient()).measure(server.url("/index.m3u8").toString())

            // 100 KB/s over 3000 s.
            assertEquals(MeasuredVideoSize(bytes = 300_000_000L, isEstimated = true), size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `measure keeps the exact size for a progressive file`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Length", "1048576"))
        server.start()
        try {
            val size = VideoStreamSizeProbe(OkHttpClient()).measure(server.url("/movie.mp4").toString())

            assertEquals(MeasuredVideoSize(bytes = 1_048_576L, isEstimated = false), size)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }
}
