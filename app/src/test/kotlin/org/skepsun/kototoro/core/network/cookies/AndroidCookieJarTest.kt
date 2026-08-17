package org.skepsun.kototoro.core.network.cookies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidCookieJarTest {

	@Test
	fun `deletion covers host-only and both domain spellings`() {
		val headers = buildCookieDeletionHeaders(
			name = "cf_clearance",
			host = "comix.to",
			paths = setOf("/"),
		)

		assertEquals(4, headers.size)
		assertTrue(headers.contains("cf_clearance=;Max-Age=0"))
		assertTrue(headers.any { "; Domain=" !in it })
		assertTrue(headers.any { "; Domain=comix.to;" in it })
		assertTrue(headers.any { "; Domain=.comix.to;" in it })
		assertTrue(headers.drop(1).all { "Max-Age=0" in it && "Path=/" in it && "; Secure" in it })
	}

	@Test
	fun `deletion preserves distinct valid paths and normalizes invalid paths`() {
		val headers = buildCookieDeletionHeaders(
			name = "session",
			host = "example.test",
			paths = setOf("/browse", "invalid", "/"),
		)

		assertEquals(7, headers.size)
		assertTrue(headers.any { "Path=/browse" in it })
		assertTrue(headers.any { "Path=/;" in it })
	}
}
