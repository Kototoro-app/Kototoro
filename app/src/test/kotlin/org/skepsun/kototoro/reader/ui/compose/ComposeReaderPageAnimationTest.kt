package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderAnimation

class ComposeReaderPageAnimationTest {

	@Test
	fun `none animation holds current page until halfway`() {
		assertEquals(
			-0.4f,
			resolve(ReaderAnimation.NONE, pageOffset = 0.4f).translationFactor,
		)
		assertEquals(1f, resolve(ReaderAnimation.NONE, pageOffset = 0.6f).translationFactor)
	}

	@Test
	fun `advanced animation behaves like a cover slide`() {
		val forwardCurrent = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = 0.4f,
			navigationProgress = 0.4f,
			isSettledPage = true,
		)
		val forwardIncoming = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = -0.6f,
			navigationProgress = 0.4f,
			isIncomingPage = true,
		)
		val backwardCurrent = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = -0.4f,
			navigationProgress = -0.4f,
			isSettledPage = true,
		)
		val backwardIncoming = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = 0.6f,
			navigationProgress = -0.4f,
			isIncomingPage = true,
		)

		assertEquals(0f, forwardCurrent.translationFactor)
		assertEquals(1f, forwardCurrent.zIndex)
		assertEquals(-0.6f, forwardIncoming.translationFactor)
		assertEquals(0f, forwardIncoming.zIndex)
		assertEquals(-0.4f, backwardCurrent.translationFactor)
		assertEquals(0f, backwardCurrent.zIndex)
		assertEquals(0f, backwardIncoming.translationFactor)
		assertEquals(1f, backwardIncoming.zIndex)
	}

	@Test
	fun `advanced animation keeps the settled page identity across midpoint`() {
		val forwardCurrent = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = 0.5f,
			navigationProgress = 0.5f,
			isSettledPage = true,
		)
		val forwardIncoming = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = -0.5f,
			navigationProgress = 0.5f,
			isIncomingPage = true,
		)

		assertEquals(1f, forwardCurrent.zIndex)
		assertEquals(0f, forwardIncoming.zIndex)
		assertEquals(0f, forwardCurrent.translationFactor)
		assertEquals(-0.5f, forwardIncoming.translationFactor)
	}

	@Test
	fun `advanced animation mirrors cover compensation in reversed layout`() {
		val forwardIncoming = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = -0.6f,
			isReversed = true,
			navigationProgress = 0.4f,
			isIncomingPage = true,
		)
		val backwardCurrent = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = -0.4f,
			isReversed = true,
			navigationProgress = -0.4f,
			isSettledPage = true,
		)
		val backwardIncoming = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = 0.6f,
			isReversed = true,
			navigationProgress = -0.4f,
			isIncomingPage = true,
		)

		assertEquals(0.6f, forwardIncoming.translationFactor)
		assertEquals(0f, forwardIncoming.zIndex)
		assertEquals(0.4f, backwardCurrent.translationFactor)
		assertEquals(0f, backwardCurrent.zIndex)
		assertEquals(0f, backwardIncoming.translationFactor)
		assertEquals(1f, backwardIncoming.zIndex)
	}

	@Test
	fun `advanced animation never promotes the page after the incoming page`() {
		val incomingPage = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = 0f,
			navigationProgress = 1f,
			isIncomingPage = true,
		)
		val pageAfterIncoming = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = -1f,
			navigationProgress = 1f,
		)

		assertEquals(0f, incomingPage.translationFactor)
		assertEquals(0f, incomingPage.zIndex)
		assertEquals(0f, pageAfterIncoming.translationFactor)
		assertEquals(-1f, pageAfterIncoming.zIndex)
	}

	@Test
	fun `advanced navigation progress is limited to one adjacent page`() {
		assertEquals(
			1f,
			resolveAdvancedNavigationProgress(
				anchorPage = 0,
				currentPage = 2,
				currentPageOffsetFraction = -0.75f,
			),
		)
		assertEquals(
			-1f,
			resolveAdvancedNavigationProgress(
				anchorPage = 2,
				currentPage = 0,
				currentPageOffsetFraction = 0.75f,
			),
		)
	}

	@Test
	fun `advanced animation rebases when a second forward navigation starts before idle`() {
		assertEquals(
			0,
			resolveAdvancedAnimationAnchor(
				anchorPage = 0,
				currentPage = 1,
				currentPageOffsetFraction = -0.1f,
				isScrollInProgress = true,
			),
		)
		assertEquals(
			1,
			resolveAdvancedAnimationAnchor(
				anchorPage = 0,
				currentPage = 1,
				currentPageOffsetFraction = 0.1f,
				isScrollInProgress = true,
			),
		)
	}

	@Test
	fun `advanced animation rebases when a second backward navigation starts before idle`() {
		assertEquals(
			2,
			resolveAdvancedAnimationAnchor(
				anchorPage = 2,
				currentPage = 1,
				currentPageOffsetFraction = 0.1f,
				isScrollInProgress = true,
			),
		)
		assertEquals(
			1,
			resolveAdvancedAnimationAnchor(
				anchorPage = 2,
				currentPage = 1,
				currentPageOffsetFraction = -0.1f,
				isScrollInProgress = true,
			),
		)
	}

	@Test
	fun `advanced animation commits its adjacent page without waiting for pager idle`() {
		assertEquals(
			1,
			resolveAdvancedAnimationAnchor(
				anchorPage = 0,
				currentPage = 1,
				currentPageOffsetFraction = 0f,
				isScrollInProgress = true,
			),
		)
	}

	@Test
	fun `advanced animation is static when pager is settled`() {
		val idle = resolve(
			ReaderAnimation.ADVANCED,
			pageOffset = 1f,
			isSettledPage = false,
		)

		assertEquals(0f, idle.translationFactor)
		assertEquals(0f, idle.zIndex)
		assertEquals(1f, idle.alpha)
	}

	@Test
	fun `simulation layers turning page above shaded revealed page`() {
		val turning = resolve(ReaderAnimation.SIMULATION, pageOffset = -0.5f)
		val revealed = resolve(ReaderAnimation.SIMULATION, pageOffset = 0.5f)

		assertEquals(1f, turning.zIndex)
		assertEquals(0.5f, turning.foldProgress)
		assertEquals(0f, turning.revealedPageShade)
		assertEquals(0f, revealed.zIndex)
		assertEquals(0f, revealed.foldProgress)
		assertEquals(0.08f, revealed.revealedPageShade)
	}

	@Test
	fun `simulation mirrors turning edge in reversed mode`() {
		val transform = resolve(
			ReaderAnimation.SIMULATION,
			pageOffset = 0.5f,
			isReversed = true,
		)

		assertEquals(1f, transform.zIndex)
		assertEquals(0.5f, transform.foldProgress)
		assertEquals(-0.5f, transform.translationFactor)
	}

	@Test
	fun `page curl uses touched corner for horizontal reading`() {
		val topRight = curl(progress = 0.5f)
		val topLeft = curl(progress = 0.5f, isReversed = true)

		assertTrue(topRight.topCurlOffset.x >= 0f)
		assertTrue(topRight.bottomCurlOffset.x >= 0f)
		assertTrue(topLeft.topCurlOffset.x <= 1000f)
		assertTrue(topLeft.bottomCurlOffset.x <= 1000f)
	}

	@Test
	fun `page curl geometry stays finite throughout horizontal and vertical turns`() {
		listOf(false, true).forEach { isVertical ->
			listOf(false, true).forEach { isReversed ->
				listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
					val geometry = curl(
						progress = progress,
						isVertical = isVertical,
						isReversed = isReversed,
					)
					val points = geometry.frontPath + geometry.backPath + listOf(
						geometry.topCurlOffset,
						geometry.bottomCurlOffset,
					)
					assertTrue(
						points.all { it.x.isFinite() && it.y.isFinite() } && geometry.angle.isFinite(),
						"vertical=$isVertical reversed=$isReversed progress=$progress",
					)
				}
			}
		}
	}

	@Test
	fun `horizontal curl direction follows physical drag`() {
		assertEquals(
			false,
			resolvePageCurlFromStart(
				isVertical = false,
				isReadingReversed = false,
				horizontalDragFraction = -0.2f,
			),
		)
		assertEquals(
			false,
			resolvePageCurlFromStart(
				isVertical = false,
				isReadingReversed = false,
				horizontalDragFraction = 0.2f,
			),
		)
		assertEquals(
			true,
			resolvePageCurlFromStart(
				isVertical = false,
				isReadingReversed = true,
				horizontalDragFraction = -0.2f,
			),
		)
	}

	@Test
	fun `horizontal curl starts from selected page edge`() {
		assertEquals(
			Offset(1f, 1f),
			resolvePageCurlStartFraction(
				downFraction = Offset(0.62f, 0.85f),
				isVertical = false,
				curlFromStart = false,
			),
		)
		assertEquals(
			Offset(0f, 0f),
			resolvePageCurlStartFraction(
				downFraction = Offset(0.88f, 0.15f),
				isVertical = false,
				curlFromStart = true,
			),
		)
	}

	private fun resolve(
		animation: ReaderAnimation,
		pageOffset: Float,
		isVertical: Boolean = false,
		isReversed: Boolean = false,
		navigationProgress: Float = 0f,
		isSettledPage: Boolean = false,
		isIncomingPage: Boolean = false,
	) = resolveComposeReaderPageTransform(
		animation,
		pageOffset,
		isVertical,
		isReversed,
		navigationProgress,
		isSettledPage,
		isIncomingPage,
	)

	private fun curl(
		progress: Float,
		isVertical: Boolean = false,
		isReversed: Boolean = false,
	) = calculatePageCurlGeometry(
		size = Size(1000f, 1600f),
		progress = progress,
		touchFraction = Offset(0.75f, 0.85f),
		isVertical = isVertical,
		isReversed = isReversed,
	)
}
