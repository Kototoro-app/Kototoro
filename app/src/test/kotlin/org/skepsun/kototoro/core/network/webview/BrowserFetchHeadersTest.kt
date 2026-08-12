package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BrowserFetchHeadersTest {
	@Test
	fun `lets chromium generate browser managed request headers`() {
		val headers = browserFetchHeaders(
			linkedMapOf(
				"User-Agent" to "extension user agent",
				"Origin" to "https://example.com",
				"Sec-Fetch-Site" to "same-origin",
				"sec-ch-ua-platform" to "Android",
				"Accept" to "application/json",
				"Content-Type" to "application/json; charset=utf-8",
				"X-Requested-With" to "XMLHttpRequest",
			),
		)

		assertFalse(headers.keys.any { it.startsWith("sec-", ignoreCase = true) })
		assertFalse(headers.keys.any { it.equals("User-Agent", ignoreCase = true) })
		assertFalse(headers.keys.any { it.equals("Origin", ignoreCase = true) })
		assertEquals("application/json", headers["Accept"])
		assertEquals("application/json; charset=utf-8", headers["Content-Type"])
		assertEquals("XMLHttpRequest", headers["X-Requested-With"])
	}
}
