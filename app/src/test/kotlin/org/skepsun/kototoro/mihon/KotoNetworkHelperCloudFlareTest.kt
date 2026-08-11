package org.skepsun.kototoro.mihon

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.mihon.compat.KotoNetworkHelper
import org.skepsun.kototoro.mihon.compat.MihonRequestContext

class KotoNetworkHelperCloudFlareTest {

    @Test
    fun `captcha response is delegated to shared resolver with request context`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("server", "cloudflare")
                    .setHeader("content-type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                            <head><title>Just a moment...</title></head>
                            <body><div id="challenge-stage"></div></body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            val network = KotoNetworkHelper(OkHttpClient(), CookieJar.NO_COOKIES)
            val source = ContentSource("MIHON_TEST")
            val request = Request.Builder()
                .url(server.url("/api/list?page=1"))
                .header("User-Agent", "shared-resolver-test")
                .header("Referer", server.url("/").toString())
                .build()

            val error = assertThrows(CloudFlareProtectedException::class.java) {
                MihonRequestContext.withSourceBlocking(source) {
                    network.client.newCall(request).execute().use { }
                }
            }

            assertEquals(source.name, error.source.name)
            assertEquals("shared-resolver-test", error.headers["User-Agent"])
            assertEquals(server.url("/api/list?page=1").toString(), error.url)
        }
    }
}
