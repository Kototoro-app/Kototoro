package org.skepsun.kototoro.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GZipInterceptorTest {

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
	fun `retries GET with gzip after request format failure`() {
		server.enqueue(MockResponse().setResponseCode(400))
		server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
		val client = OkHttpClient.Builder()
			.addInterceptor(GZipInterceptor())
			.build()

		client.newCall(Request.Builder().url(server.url("/manga")).build()).execute().use { response ->
			assertEquals(200, response.code)
		}

		assertNull(server.takeRequest().getHeader("Content-Encoding"))
		assertEquals("gzip", server.takeRequest().getHeader("Content-Encoding"))
	}

	@Test
	fun `does not retry successful GET`() {
		server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
		val client = OkHttpClient.Builder()
			.addInterceptor(GZipInterceptor())
			.build()

		client.newCall(Request.Builder().url(server.url("/manga")).build()).execute().use { response ->
			assertEquals(200, response.code)
		}

		assertNull(server.takeRequest().getHeader("Content-Encoding"))
	}
}
