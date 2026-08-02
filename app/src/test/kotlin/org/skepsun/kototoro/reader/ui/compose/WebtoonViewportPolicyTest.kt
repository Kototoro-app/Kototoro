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
	fun `expanding promoted chapter requires stable anchor restoration`() {
		val previousChapterEnd = 105L
		val promotedChapterStart = 201L
		val previousWindow = listOf(101L, 102L, 103L, 104L, previousChapterEnd, promotedChapterStart, 202L)
		val promotedWindow = listOf(104L, previousChapterEnd, promotedChapterStart) + (202L..230L)

		assertTrue(
			requiresWebtoonAnchorRestore(
				previousPageKeys = previousWindow,
				pageKeys = promotedWindow,
				anchorPageKey = previousChapterEnd,
			),
		)
		assertFalse(
			requiresWebtoonAnchorRestore(
				previousPageKeys = promotedWindow,
				pageKeys = promotedWindow + 231L,
				anchorPageKey = previousChapterEnd,
			),
		)
	}

	@Test
	fun `resolves visible pages by stable keys after leading chapter is trimmed`() {
		val visibleLowerKey = 201L
		val visibleUpperKey = 203L
		val trimmedPageKeys = listOf(200L, visibleLowerKey, 202L, visibleUpperKey, 300L)

		assertEquals(
			1..3,
			resolveWebtoonVisiblePageRange(
				pageKeys = trimmedPageKeys,
				lowerPageKey = visibleLowerKey,
				upperPageKey = visibleUpperKey,
			),
		)
	}

	@Test
	fun `ignores stale visible keys removed from current page window`() {
		assertEquals(
			null,
			resolveWebtoonVisiblePageRange(
				pageKeys = listOf(200L, 201L, 202L),
				lowerPageKey = 101L,
				upperPageKey = 102L,
			),
		)
	}

	@Test
	fun `selects last page whose end is visible across chapter boundary`() {
		assertEquals(
			201L,
			resolveLastEndVisibleWebtoonPageKey(
				items = listOf(
					WebtoonVisibleItem(pageKey = 105L, offsetPx = -200, sizePx = 400),
					WebtoonVisibleItem(pageKey = 201L, offsetPx = 200, sizePx = 800),
				),
				viewportStartPx = 0,
				viewportEndPx = 1000,
			),
		)
	}

	@Test
	fun `keeps current page while adjacent page end is outside viewport`() {
		assertEquals(
			105L,
			resolveLastEndVisibleWebtoonPageKey(
				items = listOf(
					WebtoonVisibleItem(pageKey = 105L, offsetPx = -100, sizePx = 900),
					WebtoonVisibleItem(pageKey = 201L, offsetPx = 800, sizePx = 900),
				),
				viewportStartPx = 0,
				viewportEndPx = 1000,
			),
		)
	}

	@Test
	fun `configuration change freezes viewport tracking until anchor restoration`() {
		assertFalse(
			shouldTrackWebtoonViewport(
				isAnchorRestorePending = false,
				isPageWindowAnchorShifted = false,
				viewportConfigurationChanged = true,
				isViewportLayoutReady = true,
			),
		)
		assertFalse(
			shouldTrackWebtoonViewport(
				isAnchorRestorePending = false,
				isPageWindowAnchorShifted = true,
				viewportConfigurationChanged = false,
				isViewportLayoutReady = true,
			),
		)
		assertFalse(
			shouldTrackWebtoonViewport(
				isAnchorRestorePending = false,
				isPageWindowAnchorShifted = false,
				viewportConfigurationChanged = false,
				isViewportLayoutReady = false,
			),
		)
		assertTrue(
			shouldTrackWebtoonViewport(
				isAnchorRestorePending = false,
				isPageWindowAnchorShifted = false,
				viewportConfigurationChanged = false,
				isViewportLayoutReady = true,
			),
		)
	}

	@Test
	fun `one pixel placeholder layout cannot expand the chapter window`() {
		assertFalse(
			isWebtoonViewportLayoutReady(
				visibleItemSizesPx = List(132) { 1 },
				viewportHeightPx = 2528,
			),
		)
		assertTrue(
			isWebtoonViewportLayoutReady(
				visibleItemSizesPx = listOf(2528, 900),
				viewportHeightPx = 2528,
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

	@Test
	fun `fractional fitted height does not expose a background pixel`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 500),
			measureWebtoonViewport(2000, 500, 1000, 1001),
		)
	}
}
