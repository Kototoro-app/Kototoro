package org.skepsun.kototoro.scrobbling.mal.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider

class MALDiscoveryNetworkModuleTest {
    private fun storage(token: String?): ScrobblerStorage = mockk<ScrobblerStorage>().also {
        every { it.accessToken } returns token
    }

    private fun localBuilder(server: MockWebServer, storage: ScrobblerStorage) = OkHttpClient.Builder()
        .addInterceptor(MALInterceptor(storage, "fixture-client"))
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
            chain.proceed(request.newBuilder().url(server.url(path)).build())
        }

    @Test
    fun `public query without token keeps client id and JSON interceptor headers`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            createKtorOkHttpClient(localBuilder(server, storage(null)).build()).use { http ->
                assertEquals("""{"data":[]}""", MALDiscoveryApi(http, "fixture-client").ranking(MALMediaType.MANGA))
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/v2/manga/ranking", request.requestUrl!!.encodedPath)
            assertEquals("fixture-client", request.getHeader("X-MAL-CLIENT-ID"))
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun `qualified client keeps existing bearer token`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            createKtorOkHttpClient(localBuilder(server, storage("fixture-token")).build()).use { http ->
                MALDiscoveryApi(http, "fixture-client").search("葬送", 0, MALMediaType.ANIME)
            }
            assertEquals("Bearer fixture-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
    }

    @Test
    fun `401 keeps original authenticator refresh and retries with refreshed token`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("{}"))
            var token = "old"
            val storage = mockk<ScrobblerStorage>()
            every { storage.accessToken } answers { token }
            val repository = mockk<MALRepository>()
            coEvery { repository.authorize(null) } coAnswers { token = "new" }
            val base = localBuilder(server, storage)
                .authenticator(MALAuthenticator(storage, Provider { repository })).build()
            createKtorOkHttpClient(base).use { http ->
                assertEquals("{}", MALDiscoveryApi(http, "fixture-client").ranking(MALMediaType.ANIME))
            }
            assertEquals("Bearer old", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            coVerify(exactly = 1) { repository.authorize(null) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `unresolved 401 and 403 preserve application auth exception without protocol retries`() = runBlocking {
        for (code in listOf(401, 403)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(code).setBody("unread auth error"))
                val body = AtomicReference<ResponseBody>()
                val base = localBuilder(server, storage(null)).addNetworkInterceptor { chain ->
                    chain.proceed(chain.request()).also { body.set(it.body) }
                }.build()
                createKtorOkHttpClient(base).use { http ->
                    val error = runCatching {
                        MALDiscoveryApi(http, "fixture-client").ranking(MALMediaType.ANIME)
                    }.exceptionOrNull()
                    assertTrue(error is ScrobblerAuthRequiredException, "Expected auth exception, got $error")
                    assertEquals(ScrobblerService.MAL, (error as ScrobblerAuthRequiredException).scrobbler)
                    assertNotNull(body.get())
                    assertThrows(IllegalStateException::class.java) { body.get().source().request(1) }
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `HTML response preserves interceptor title error`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>Fixture unavailable</title></head></html>"))
            createKtorOkHttpClient(localBuilder(server, storage(null)).build()).use { http ->
                val error = runCatching {
                    MALDiscoveryApi(http, "fixture-client").ranking(MALMediaType.ANIME)
                }.exceptionOrNull()
                assertTrue(error is java.io.IOException)
                assertEquals("Fixture unavailable", error?.message)
            }
        }
    }

    @Test
    fun `query encoding and surrogate truncation match old OkHttp decoded values`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val queries = listOf("葬送 + & / ? # %", "x".repeat(63) + "🦊")
            createKtorOkHttpClient(localBuilder(server, storage(null)).build()).use { http ->
                val api = MALDiscoveryApi(http, "fixture-client")
                for (query in queries) {
                    server.enqueue(MockResponse().setBody("{}"))
                    api.search(query, 0, MALMediaType.ANIME)
                    val expected = "https://api.myanimelist.net/v2/anime".toHttpUrl().newBuilder()
                        .addQueryParameter("q", query.take(64)).build().queryParameter("q")
                    assertEquals(expected, server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("q"))
                }
            }
        }
    }

    @Test
    fun `cross origin redirect retains old bearer stripping`() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start()
                target.start()
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/redirected")))
                target.enqueue(MockResponse().setBody("{}"))
                createKtorOkHttpClient(localBuilder(origin, storage("fixture-token")).build()).use { http ->
                    MALDiscoveryApi(http, "fixture-client").ranking(MALMediaType.ANIME)
                }
                assertEquals(
                    "Bearer fixture-token",
                    origin.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"),
                )
                assertNull(target.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `active query cancellation stops network call without retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            createKtorOkHttpClient(localBuilder(server, storage(null)).build()).use { http ->
                val api = MALDiscoveryApi(http, "fixture-client")
                val job = launch(Dispatchers.Default) { api.ranking(MALMediaType.ANIME) }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(5000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
            assertEquals(1, server.requestCount)
        }
    }
}
