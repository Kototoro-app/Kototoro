package org.skepsun.kototoro.cloudstream.runtime

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class CloudstreamNewPipeDownloaderTest {

    @Test
    fun `requests carry NewPipe's method, body and headers over the content client`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}").addHeader("X-Test", "1"))
            server.start()
            val url = server.url("/youtubei/v1/player").toString()

            val response = CloudstreamNewPipeDownloader(OkHttpClient()).execute(
                Request.newBuilder()
                    .post(url, "{}".toByteArray())
                    .headers(mapOf("Accept-Language" to listOf("en-GB, en;q=0.9"), "X-Multi" to listOf("a", "b")))
                    .build(),
            )

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("{}", recorded.body.readUtf8())
            assertEquals("en-GB, en;q=0.9", recorded.getHeader("Accept-Language"))
            assertEquals(listOf("a", "b"), recorded.headers.values("X-Multi"))
            assertEquals(com.lagradost.cloudstream3.USER_AGENT, recorded.getHeader("User-Agent"))
            assertEquals(200, response.responseCode())
            assertEquals("{\"ok\":true}", response.responseBody())
            assertEquals(listOf("1"), response.responseHeaders()["X-Test"])
            assertEquals(url, response.latestUrl())
        }
    }

    @Test
    fun `a rate limit is reported as NewPipe's captcha challenge`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.start()

            assertThrows<ReCaptchaException> {
                CloudstreamNewPipeDownloader(OkHttpClient()).execute(
                    Request.newBuilder().get(server.url("/watch?v=abc").toString()).build(),
                )
            }
        }
    }
}
