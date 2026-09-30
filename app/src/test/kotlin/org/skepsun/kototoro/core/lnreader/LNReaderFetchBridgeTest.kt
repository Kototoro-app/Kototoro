package org.skepsun.kototoro.core.lnreader

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentSource as ParserContentSource

class LNReaderFetchBridgeTest {

	private lateinit var server: MockWebServer

	@BeforeEach
	fun setUp() {
		server = MockWebServer()
		server.start()
	}

	@AfterEach
	fun tearDown() {
		server.shutdown()
	}

	@Test
	fun `request init referrer is forwarded as referer header`() = runBlocking {
		server.enqueue(MockResponse().setBody("{}"))
		val referrer = "https://example.com/novel/chapter-11"
		val bridge = LNReaderFetchBridge(OkHttpClient(), "TEST_PLUGIN")

		bridge.fetch(
			server.url("chapter").toString(),
			JSONObject()
				.put("method", "POST")
				.put("referrer", referrer)
				.toString(),
		)

		server.takeRequest().let { request ->
			assertEquals(referrer, request.getHeader("Referer"))
			assertEquals("https://example.com", request.getHeader("Origin"))
		}
	}

	@Test
	fun `text fetch carries authoritative source tag`() = runBlocking {
		server.enqueue(MockResponse().setBody("{}"))
		val source = ContentSource("LNREADER_TEST")
		var capturedSource: ParserContentSource? = null
		val client = OkHttpClient.Builder()
			.addInterceptor { chain ->
				capturedSource = chain.request().tag(ParserContentSource::class.java)
				chain.proceed(chain.request())
			}
			.build()

		LNReaderFetchBridge(client, "TEST_PLUGIN", source)
			.fetch(server.url("chapter").toString())

		assertSame(source, capturedSource)
	}

	@Test
	fun `http errors retain status and body for plugin handling`() = runBlocking {
		server.enqueue(MockResponse().setResponseCode(404).setBody("missing chapter"))
		val bridge = LNReaderFetchBridge(OkHttpClient(), "TEST_PLUGIN")
		val response = JSONObject(bridge.fetch(server.url("chapter").toString()))
		assertFalse(response.getBoolean("ok"))
		assertEquals(404, response.getInt("status"))
		assertEquals("missing chapter", response.getString("text"))
	}

	@Test
	fun `cancelled fetch propagates coroutine cancellation`() {
		server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
		val bridge = LNReaderFetchBridge(OkHttpClient(), "TEST_PLUGIN")
		assertThrows(TimeoutCancellationException::class.java) {
			runBlocking {
				withTimeout(500) { bridge.fetch(server.url("chapter").toString()) }
			}
		}
	}

	@Test
	fun `binary fetch preserves cloudflare exception for repository boundary`() {
		val protectedException = CloudFlareProtectedException(
			url = server.url("challenge").toString(),
			source = ContentSource("LNREADER_TEST"),
			headers = okhttp3.Headers.Builder().build(),
		)
		val client = OkHttpClient.Builder()
			.addInterceptor { throw protectedException }
			.build()
		val bridge = LNReaderFetchBridge(client, "TEST_PLUGIN")

		bridge.fetchBinary(
			url = server.url("proto").toString(),
			bodyBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
		)

		assertSame(protectedException, bridge.pendingFatalException)
	}
}
