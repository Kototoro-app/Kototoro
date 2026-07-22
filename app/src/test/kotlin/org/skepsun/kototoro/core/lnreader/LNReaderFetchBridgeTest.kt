package org.skepsun.kototoro.core.lnreader

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
}
