package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComposeReaderScrollRequestTest {

	@Test
	fun `coalesced scroll requests retain all pending distance`() {
		val previous = ComposeReaderScrollRequest(id = 1L, delta = 1, cumulativeDelta = 1L, smooth = false)
		val current = ComposeReaderScrollRequest(id = 11L, delta = 1, cumulativeDelta = 11L, smooth = false)

		assertEquals(10L, resolveScrollRequestDelta(previous, current))
	}

	@Test
	fun `first scroll request applies only its own distance`() {
		val current = ComposeReaderScrollRequest(id = 11L, delta = 2, cumulativeDelta = 22L, smooth = false)

		assertEquals(2L, resolveScrollRequestDelta(previous = null, current = current))
	}
}
