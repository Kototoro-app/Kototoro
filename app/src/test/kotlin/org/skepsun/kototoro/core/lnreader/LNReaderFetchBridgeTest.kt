package org.skepsun.kototoro.core.lnreader

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.ContentSource

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
	fun `request init referrer is forwarded as referer header`() {
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

		server.takeRequest().also { request ->
			assertEquals(referrer, request.getHeader("Referer"))
			assertEquals("https://example.com", request.getHeader("Origin"))
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
