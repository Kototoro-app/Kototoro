package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeReaderControllerPolicyTest {

	@Test
	fun `old pager callback cannot consume layout transition request`() {
		assertFalse(shouldAcceptReaderPosition(position = 9, requestedPosition = 12))
	}

	@Test
	fun `target pager callback completes layout transition request`() {
		assertTrue(shouldAcceptReaderPosition(position = 12, requestedPosition = 12))
	}

	@Test
	fun `neighbour page callback completes a double-page transition request`() {
		assertTrue(shouldAcceptReaderPosition(position = 11, requestedPosition = 12))
	}
	@Test
	fun `normal paging accepts every settled position`() {
		assertTrue(shouldAcceptReaderPosition(position = 13, requestedPosition = null))
	}
}
