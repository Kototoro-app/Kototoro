package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebtoonViewportPolicyTest {

	@Test
	fun `prepending chapter pages preserves the visible page anchor`() {
		val currentPageKey = 18301L
		val pagesAfterPrepend = listOf(18201L, 18202L, currentPageKey, 18302L)

		assertEquals(2, resolveWebtoonAnchorPosition(pagesAfterPrepend, currentPageKey))
	}

	@Test
	fun `prepending pages requires an anchor restore but appending does not`() {
		val currentPageKey = 18301L
		val original = listOf(currentPageKey, 18302L)

		assertEquals(
			true,
			hasWebtoonAnchorShifted(original, listOf(18201L, 18202L) + original, currentPageKey),
		)
		assertEquals(
			false,
			hasWebtoonAnchorShifted(original, original + 18401L, currentPageKey),
		)
	}

	@Test
	fun `configuration change freezes viewport tracking until anchor restoration`() {
		assertFalse(
			shouldTrackWebtoonViewport(
				isAnchorRestorePending = false,
				anchorShiftPending = false,
				viewportConfigurationChanged = true,
			),
		)
		assertTrue(
			shouldTrackWebtoonViewport(
				isAnchorRestorePending = false,
				anchorShiftPending = false,
				viewportConfigurationChanged = false,
			),
		)
	}

	@Test
	fun `zoomed out canvas keeps the inverse scaled layout centered`() {
		assertEquals(
			WebtoonCanvasOffsetBounds(
				minX = 0f,
				maxX = 0f,
				minY = 0f,
				maxY = 0f,
			),
			resolveWebtoonCanvasOffsetBounds(1000, 2000, 0.8f),
		)
	}

	@Test
	fun `zoomed in canvas uses symmetric viewport bounds`() {
		assertEquals(
			WebtoonCanvasOffsetBounds(
				minX = -500f,
				maxX = 500f,
				minY = -500f,
				maxY = 500f,
			),
			resolveWebtoonCanvasOffsetBounds(1000, 1000, 2f),
		)
	}

	@Test
	fun `zoomed out layout reserves inverse scaled viewport`() {
		assertEquals(1250, resolveWebtoonLayoutViewportHeight(1000, 0.8f))
		assertEquals(1000, resolveWebtoonLayoutViewportHeight(1000, 1f))
	}

	@Test
	fun `boundary handoff follows the opposite direction and accounts for scale`() {
		assertEquals(50, resolveWebtoonBoundaryHandoff(scale = 2f, desiredY = -600f, boundedY = -500f))
		assertEquals(-50, resolveWebtoonBoundaryHandoff(scale = 2f, desiredY = 600f, boundedY = 500f))
		assertEquals(0, resolveWebtoonBoundaryHandoff(scale = 1f, desiredY = 600f, boundedY = 0f))
	}

	@Test
	fun `unknown image reserves complete viewport`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 2000),
			measureWebtoonViewport(2000, 1000, null, null),
		)
	}

	@Test
	fun `boundary handoff ignores non finite gesture values`() {
		assertEquals(0, resolveWebtoonBoundaryHandoff(scale = 2f, desiredY = Float.NaN, boundedY = 0f))
		assertEquals(0, resolveWebtoonBoundaryHandoff(scale = Float.NaN, desiredY = 0f, boundedY = 0f))
	}

	@Test
	fun `long image keeps its natural fitted height`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 5000),
			measureWebtoonViewport(2000, 1000, 1000, 5000),
		)
	}

	@Test
	fun `short image keeps its natural fitted height`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 750),
			measureWebtoonViewport(2000, 1000, 2000, 1500),
		)
	}
}
