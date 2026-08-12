package org.skepsun.kototoro.core.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrowserTransportRequestTest {
	@Test
	fun `adds request body content type when it is not an explicit header`() {
		val request = Request.Builder()
			.url("https://example.com/api")
			.post("{}".toRequestBody("application/json".toMediaType()))
			.header("Accept", "application/json")
			.build()

		val headers = request.browserTransportHeaders()

		assertEquals("application/json; charset=utf-8", headers["Content-Type"])
		assertEquals("application/json", headers["Accept"])
	}

	@Test
	fun `keeps explicit content type header`() {
		val request = Request.Builder()
			.url("https://example.com/api")
			.post("{}".toRequestBody("application/json".toMediaType()))
			.header("Content-Type", "application/problem+json")
			.build()

		assertEquals("application/problem+json", request.browserTransportHeaders()["Content-Type"])
	}
}
