package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.domain.TapGridArea

class ComposeReaderTapGridTest {

	@Test
	fun `maps viewport thirds to grid areas`() {
		val size = IntSize(300, 600)
		assertEquals(TapGridArea.TOP_LEFT, resolveTapGridArea(Offset(10f, 10f), size))
		assertEquals(TapGridArea.CENTER, resolveTapGridArea(Offset(150f, 300f), size))
		assertEquals(TapGridArea.BOTTOM_RIGHT, resolveTapGridArea(Offset(299f, 599f), size))
	}

	@Test
	fun `rejects an empty viewport`() {
		assertNull(resolveTapGridArea(Offset.Zero, IntSize.Zero))
	}

	@Test
	fun `detects a second tap within the configured window`() {
		assertTrue(
			isTapGridDoubleTapCandidate(
				previousPosition = Offset(100f, 100f),
				previousTapAt = 1_000L,
				position = Offset(104f, 96f),
				now = 1_200L,
				timeoutMillis = 300L,
				doubleTapSlop = 8f,
			),
		)
	}

	@Test
	fun `rejects a tap outside the double tap window or slop`() {
		assertFalse(
			isTapGridDoubleTapCandidate(
				previousPosition = Offset(100f, 100f),
				previousTapAt = 1_000L,
				position = Offset(120f, 100f),
				now = 1_200L,
				timeoutMillis = 300L,
				doubleTapSlop = 8f,
			),
		)
	}

	@Test
	fun `treats a press after the double tap timeout as another single tap`() {
		assertFalse(
			isTapGridDoubleTapCandidate(
				previousPosition = Offset(100f, 100f),
				previousTapAt = 1_000L,
				position = Offset(100f, 100f),
				now = 1_320L,
				timeoutMillis = 300L,
				doubleTapSlop = 8f,
			),
		)
	}

	@Test
	fun `requires a previous tap and the minimum interval`() {
		assertFalse(
			isTapGridDoubleTapCandidate(
				previousPosition = null,
				previousTapAt = 1_000L,
				position = Offset(100f, 100f),
				now = 1_200L,
				minTimeMillis = 50L,
				timeoutMillis = 300L,
				doubleTapSlop = 8f,
			),
		)
		assertFalse(
			isTapGridDoubleTapCandidate(
				previousPosition = Offset(100f, 100f),
				previousTapAt = 1_000L,
				position = Offset(100f, 100f),
				now = 1_020L,
				minTimeMillis = 50L,
				timeoutMillis = 300L,
				doubleTapSlop = 8f,
			),
		)
	}

	@Test
	fun `uses the system double tap radius instead of drag touch slop`() {
		assertTrue(
			isTapGridDoubleTapCandidate(
				previousPosition = Offset(100f, 100f),
				previousTapAt = 1_000L,
				position = Offset(124f, 118f),
				now = 1_200L,
				timeoutMillis = 300L,
				doubleTapSlop = 32f,
			),
		)
	}
}
