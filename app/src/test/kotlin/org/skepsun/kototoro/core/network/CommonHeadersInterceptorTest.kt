package org.skepsun.kototoro.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommonHeadersInterceptorTest {

	@Test
	fun `desktop cloudstream user agent emits desktop client hints`() {
		val hints = browserClientHints(
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
				"AppleWebKit/537.36 Chrome/149.0.0.0 Safari/537.36",
		)

		assertEquals("?0", hints.mobile)
		assertEquals("Windows", hints.platform)
	}

	@Test
	fun `android webview user agent emits mobile client hints`() {
		val hints = browserClientHints(
			"Mozilla/5.0 (Linux; Android 16; Device Build/Test; wv) " +
				"AppleWebKit/537.36 Chrome/150.0.0.0 Mobile Safari/537.36",
		)

		assertEquals("?1", hints.mobile)
		assertEquals("Android", hints.platform)
	}
}
