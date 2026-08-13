package org.skepsun.kototoro.core.exceptions.resolve

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.model.UnknownContentSource

class CloudFlareRequestContextTest {

	@Test
	fun `derives host source and urls from exception`() {
		val exception = CloudFlareProtectedException(
			url = "https://kagane.to/",
			source = TestContentSource,
			headers = Headers.headersOf("User-Agent", "Mozilla/5.0", "Accept", "application/json"),
			method = "POST",
			body = "{\"query\":\"test\"}",
			originalUrl = "https://kagane.to/api/v2/search/series",
		)

		val context = CloudFlareRequestContext.from(exception)

		assertEquals("kagane.to", context.host)
		assertEquals("https://kagane.to/", context.challengeUrl)
		assertEquals("https://kagane.to/api/v2/search/series", context.originalRequestUrl)
		assertEquals("POST", context.method)
		assertEquals("{\"query\":\"test\"}", context.body)
	}

	@Test
	fun `copies user agent and headers without exposing sensitive values`() {
		val exception = CloudFlareProtectedException(
			url = "https://kagane.to/",
			source = TestContentSource,
			headers = Headers.headersOf(
				"User-Agent", "Mozilla/5.0",
				"Authorization", "Bearer secret-token",
			),
		)

		val context = CloudFlareRequestContext.from(exception)

		assertEquals("Mozilla/5.0", context.userAgent)
		assertEquals("Bearer secret-token", context.headers["Authorization"])
	}

	@Test
	fun `original url defaults to challenge url for legacy exceptions`() {
		val exception = CloudFlareProtectedException(
			url = "https://kagane.to/",
			source = TestContentSource,
			headers = Headers.headersOf(),
		)

		val context = CloudFlareRequestContext.from(exception)

		assertEquals("https://kagane.to/", context.originalRequestUrl)
		assertEquals("https://kagane.to/", context.challengeUrl)
	}

	@Test
	fun `missing user agent resolves to null`() {
		val exception = CloudFlareProtectedException(
			url = "https://kagane.to/",
			source = TestContentSource,
			headers = Headers.headersOf(),
		)

		assertNull(CloudFlareRequestContext.from(exception).userAgent)
	}

	@Test
	fun `missing source defaults to UnknownContentSource`() {
		val exception = CloudFlareProtectedException(
			url = "https://kagane.to/",
			source = null,
			headers = Headers.headersOf(),
		)

		assertEquals(UnknownContentSource, CloudFlareRequestContext.from(exception).source)
	}

	@Test
	fun `invalid url falls back to raw string as host`() {
		val exception = CloudFlareProtectedException(
			url = "not-a-valid-url",
			source = TestContentSource,
			headers = Headers.headersOf(),
		)

		assertEquals("not-a-valid-url", CloudFlareRequestContext.from(exception).host)
	}
}
