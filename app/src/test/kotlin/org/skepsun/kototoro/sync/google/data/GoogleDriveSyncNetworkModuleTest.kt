package org.skepsun.kototoro.sync.google.data

import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.io.IOException

class GoogleDriveSyncNetworkModuleTest {

    /** Redirect only the initial Google endpoint to a local fixture, preserving real engine behavior. */
    private fun localClient(server: MockWebServer): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
            chain.proceed(request.newBuilder().url(server.url(path)).build())
        }
        .build()

    @Test
    fun `Android client retains original timeout and retry settings`() {
        createGoogleDriveSyncHttpClient().use { client ->
            val okHttp = (client.engine.config as OkHttpConfig).preconfigured!!
            assertEquals(15_000, okHttp.connectTimeoutMillis)
            assertEquals(30_000, okHttp.readTimeoutMillis)
            assertEquals(30_000, okHttp.writeTimeoutMillis)
            assertEquals(60_000, okHttp.callTimeoutMillis)
            assertTrue(okHttp.retryOnConnectionFailure)
            assertTrue(okHttp.followRedirects)
            assertTrue(okHttp.followSslRedirects)
        }
    }

    @Test
    fun `real engine sends bearer query parameters and downloads binary content`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = byteArrayOf(0, 1, -1, -128, 127)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            createGoogleDriveSyncHttpClient(localClient(server)).use { client ->
                assertArrayEquals(bytes, GoogleDriveSyncApi(client).download("fixture-token", "file-id"))
                val recorded = server.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull(recorded)
                assertEquals("GET", recorded!!.method)
                assertEquals("/drive/v3/files/file-id?alt=media", recorded.path)
                assertEquals("Bearer fixture-token", recorded.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `OkHttp strips bearer authorization when following a cross origin redirect`() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start()
                target.start()
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/version")))
                target.enqueue(MockResponse().setBody("{\"version\":\"9\"}"))
                createGoogleDriveSyncHttpClient(localClient(origin)).use { client ->
                    assertEquals("9", GoogleDriveSyncApi(client).getFileVersion("fixture-token", "file-id"))
                    val initial = origin.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals("Bearer fixture-token", initial.getHeader("Authorization"))
                    assertNull(target.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                }
            }
        }
    }

    @Test
    fun `creation redirects keep legacy POST to GET behavior before PATCH upload`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/created")))
            server.enqueue(MockResponse().setBody("{\"id\":\"created-id\"}"))
            server.enqueue(MockResponse().setResponseCode(204))
            createGoogleDriveSyncHttpClient(localClient(server)).use { client ->
                val payload = "{\"title\":\"葬送\"}".encodeToByteArray()
                assertEquals("created-id", GoogleDriveSyncApi(client).upload("fixture-token", payload, null))
                assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
                val redirected = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("GET", redirected.method)
                assertEquals(0L, redirected.bodySize)
                val uploaded = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("PATCH", uploaded.method)
                assertEquals("/upload/drive/v3/files/created-id?uploadType=media&fields=id", uploaded.path)
                assertEquals("application/json; charset=UTF-8", uploaded.getHeader("Content-Type"))
                assertArrayEquals(payload, uploaded.body.readByteArray())
            }
        }
    }

    @Test
    fun `real response body failure does not replay the download`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setBody("x".repeat(100_000))
                    .setBodyDelay(100, TimeUnit.MILLISECONDS)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            createGoogleDriveSyncHttpClient(localClient(server)).use { client ->
                val error = runCatching { GoogleDriveSyncApi(client).download("fixture-token", "file-id") }
                    .exceptionOrNull()
                assertTrue(error is IOException, "Expected body read failure, got $error")
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `cancelling an active network call stops it without a retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            createGoogleDriveSyncHttpClient(localClient(server)).use { client ->
                val job = launch(Dispatchers.Default) {
                    GoogleDriveSyncApi(client).download("fixture-token", "file-id")
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(5_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            }
        }
    }
}
