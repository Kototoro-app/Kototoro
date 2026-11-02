package org.skepsun.kototoro.cloudstream.desktop

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 49 of 517 community plugins create a CloudflareKiller; on Windows it reads the platform client's clearance. */
class CloudflareKillerTest {

    private val jar = MemoryCookieJar()
    private lateinit var previousClient: OkHttpClient

    @BeforeEach
    fun installClient() {
        previousClient = app.baseClient
        app.baseClient = OkHttpClient.Builder().cookieJar(jar).build()
    }

    @AfterEach
    fun restoreClient() {
        app.baseClient = previousClient
    }

    @Test
    fun `cookie headers carry the platform clearance for the host`() {
        val url = "https://example.test/watch/1"
        jar.saveFromResponse(
            HttpUrl.Builder().scheme("https").host("example.test").build(),
            listOf(
                Cookie.Builder().domain("example.test").name("cf_clearance").value("token").build(),
                Cookie.Builder().domain("example.test").name("session").value("abc").build(),
            ),
        )
        val killer = CloudflareKiller()

        val headers = killer.getCookieHeaders(url)

        assertEquals(USER_AGENT, headers["user-agent"])
        assertEquals("cf_clearance=token; session=abc", headers["Cookie"])
        assertEquals(mapOf("cf_clearance" to "token", "session" to "abc"), killer.savedCookies["example.test"])
    }

    @Test
    fun `a host without cookies gets only the user agent`() {
        val headers = CloudflareKiller().getCookieHeaders("https://other.test/")

        assertEquals(USER_AGENT, headers["user-agent"])
        assertNull(headers["Cookie"])
    }

    @Test
    fun `requests pass through to the platform client unchanged`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val client = app.baseClient.newBuilder().addInterceptor(CloudflareKiller()).build()

            val body = client.newCall(Request.Builder().url(server.url("/page")).build()).execute().use {
                it.body.string()
            }

            assertEquals("ok", body)
            assertEquals("/page", server.takeRequest().path)
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies += cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }
}
