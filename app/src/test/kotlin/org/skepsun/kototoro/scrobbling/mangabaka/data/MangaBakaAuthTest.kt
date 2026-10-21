package org.skepsun.kototoro.scrobbling.mangabaka.data

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage

class MangaBakaAuthTest {
    private fun storage(): ScrobblerStorage {
        val values = mutableMapOf<String, String?>()
        return mockk<ScrobblerStorage>(relaxed = true).also { storage ->
            every { storage[any()] } answers { values[firstArg()] }
            every { storage[any()] = any() } answers { values[firstArg()] = secondArg() }
        }
    }

    @Test
    fun `each authorization uses a fresh state and persisted verifier with S256`() {
        val storage = storage()
        val auth = MangaBakaAuth(OkHttpClient(), storage, "fixture-client")
        val first = auth.beginAuthorization().toHttpUrl()
        val second = auth.beginAuthorization().toHttpUrl()
        assertNotEquals(first.queryParameter("state"), second.queryParameter("state"))
        assertEquals("S256", second.queryParameter("code_challenge_method"))
        assertEquals(mangaBakaCodeChallenge(storage["oauth_verifier"]!!), second.queryParameter("code_challenge"))
        assertEquals(MANGABAKA_REDIRECT_URI, second.queryParameter("redirect_uri"))
        assertTrue(second.queryParameter("scope")!!.contains("library.write"))
    }

    @Test
    fun `mismatched state is rejected before any network exchange`() {
        val auth = MangaBakaAuth(OkHttpClient(), storage(), "fixture-client")
        auth.beginAuthorization()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { auth.exchange("code", "wrong-state") } }
    }

    @Test
    fun `new auth instance can exchange persisted PKCE once without a client secret`() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/token")).build())
            }.build()
            val storage = storage()
            val url = MangaBakaAuth(client, storage, "fixture-client").beginAuthorization().toHttpUrl()
            val verifier = storage["oauth_verifier"]!!
            server.enqueue(MockResponse().setBody("""{
                "access_token":"access-fixture","token_type":"Bearer","refresh_token":"refresh-fixture"
            }"""))
            val restored = MangaBakaAuth(client, storage, "fixture-client")
            val tokens = restored.exchange("code-fixture", url.queryParameter("state")!!)
            assertEquals("access-fixture", tokens.accessToken)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("code_verifier=$verifier"))
            assertTrue(body.contains("client_id=fixture-client"))
            assertTrue(!body.contains("client_secret"))
            // Candidate credentials are not committed before the profile is verified.
            verify(exactly = 0) { storage.accessToken = any() }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { restored.exchange("code-fixture", url.queryParameter("state")!!) }
            }
            Unit
        }
    }
}
