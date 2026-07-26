package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebtoonDoubleTapPolicyTest {

	@Test
	fun `accepts second tap within system timing and double tap slop`() {
		assertTrue(
			isWebtoonDoubleTapCandidate(
				firstTapPosition = Offset(100f, 200f),
				firstTapUpTimeMillis = 1_000,
				secondTapPosition = Offset(122f, 218f),
				secondTapDownTimeMillis = 1_120,
				minTimeMillis = 40,
				timeoutMillis = 300,
				doubleTapSlop = 32f,
			),
		)
	}

	@Test
	fun `rejects taps outside timing or spatial bounds`() {
		val firstPosition = Offset(100f, 200f)
		assertFalse(
			isWebtoonDoubleTapCandidate(firstPosition, 1_000, firstPosition, 1_020, 40, 300, 32f),
		)
		assertFalse(
			isWebtoonDoubleTapCandidate(firstPosition, 1_000, firstPosition, 1_301, 40, 300, 32f),
		)
		assertFalse(
			isWebtoonDoubleTapCandidate(firstPosition, 1_000, Offset(140f, 200f), 1_120, 40, 300, 32f),
		)
	}

	@Test
	fun `detects cumulative movement instead of per event movement`() {
		val start = Offset(0f, 0f)
		assertFalse(hasExceededWebtoonTapSlop(start, Offset(5f, 5f), touchSlop = 10f))
		assertTrue(hasExceededWebtoonTapSlop(start, Offset(9f, 9f), touchSlop = 10f))
	}
}
