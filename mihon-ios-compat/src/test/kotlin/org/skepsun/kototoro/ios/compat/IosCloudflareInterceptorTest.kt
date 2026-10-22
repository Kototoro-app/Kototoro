package org.skepsun.kototoro.ios.compat

import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor as SdkCloudflareInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.core.source.SourceChallengeException
import java.io.IOException

class IosCloudflareInterceptorTest {
    @Test
    fun `derived extension client retains the Cloudflare interceptor name required by KeiSource`() {
        val client = client(200, "<html>Books</html>").newBuilder().apply {
            check(interceptors().any { it.javaClass.simpleName == "CloudflareInterceptor" }) {
                "CloudflareInterceptor must be present in default client"
            }
        }.build()
        client.newCall(Request.Builder().url("https://site.test/list").build()).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `native challenge uses browser bytes without requiring another cookie solve`() {
        val bytes = byteArrayOf(0xc7.toByte(), 0xe1.toByte(), 0xd0.toByte(), 0xa1.toByte())
        var fetched = 0
        val client = OkHttpClient.Builder().addInterceptor(CloudflareInterceptor(browserFetch = { request ->
            fetched++
            assertEquals("/modules/article/articlelist.php?page=2", request.url.encodedPath + "?" + request.url.encodedQuery)
            assertEquals("source UA", request.header("User-Agent"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html; charset=gbk").body(bytes.toResponseBody()).build()
        })).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("challenge")
                .header("Server", "cloudflare").header("cf-mitigated", "challenge")
                .body("<title>Just a moment...</title>".toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url("https://site.test/modules/article/articlelist.php?page=2")
            .header("User-Agent", "source UA").build()).execute().use {
            assertEquals(200, it.code)
            assertArrayEquals(bytes, it.body.bytes())
        }
        assertEquals(1, fetched)
    }

    @Test
    fun `browser fallback never replays a post or treats another challenge as success`() {
        var fetched = 0
        val client = OkHttpClient.Builder().addInterceptor(CloudflareInterceptor(browserFetch = { request ->
            fetched++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("challenge")
                .header("Server", "cloudflare").header("cf-mitigated", "challenge")
                .body("challenge".toResponseBody()).build()
        })).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("challenge")
                .header("Server", "cloudflare").header("cf-mitigated", "challenge")
                .body("challenge".toResponseBody()).build()
        }.build()
        assertThrows<IOException> {
            client.newCall(Request.Builder().url("https://site.test/login").post("login".toRequestBody()).build())
                .execute().close()
        }
        assertEquals(0, fetched)
        assertThrows<IOException> {
            client.newCall(Request.Builder().url("https://site.test/list").build()).execute().close()
        }
        assertEquals(1, fetched)
    }

    private fun client(code: Int, html: String, challenge: Boolean = false): OkHttpClient =
        iosNetworkClient(OkHttpClient.Builder().addInterceptor(SdkCloudflareInterceptor { })
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .header("Server", "cloudflare").header("Content-Type", "text/html")
                    .apply { if (challenge) header("cf-mitigated", "challenge") }
                    .body(html.toResponseBody()).build()
            }.build())

    @Test
    fun `ordinary Cloudflare forbidden response is not a browser challenge`() {
        val client = client(403, "<html>Access requires a login</html>")
        client.newCall(Request.Builder().url("https://site.test/private").build()).execute().use {
            assertEquals(403, it.code)
            assertEquals("<html>Access requires a login</html>", it.body.string())
        }
    }

    @Test
    fun `challenge retains actual request path and user agent for the browser`() {
        val client = client(403, "<title>Just a moment...</title>", challenge = true)
        val request = Request.Builder().url("https://site.test/modules/article/articlelist.php?page=2")
            .header("User-Agent", "source UA").build()
        val error = assertThrows<IOException> { client.newCall(request).execute().close() }
        assertEquals("https://site.test", (error.cause as SourceChallengeException).origin)
        val session = client.interceptors.filterIsInstance<CloudflareInterceptor>().single().session("https://site.test")
        assertEquals(request.url.toString(), session?.url)
        assertEquals("source UA", session?.userAgent)
        assertNull(client.interceptors.filterIsInstance<CloudflareInterceptor>().single().session("https://other.test"))
    }

    @Test
    fun `successful page with background Cloudflare script remains readable`() {
        val html = "<html><title>Books</title><script src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'></script></html>"
        client(200, html).newCall(Request.Builder().url("https://site.test/list").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals(html, it.body.string())
        }
    }
}
