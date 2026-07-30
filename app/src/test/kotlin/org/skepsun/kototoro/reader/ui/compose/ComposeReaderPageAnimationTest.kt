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
	fun `simulation keeps unfolding direction on the turning page`() {
		val turning = resolve(
			ReaderAnimation.SIMULATION,
			pageOffset = -0.5f,
			isCurlUnfolding = true,
		)
		val revealed = resolve(
			ReaderAnimation.SIMULATION,
			pageOffset = 0.5f,
			isCurlUnfolding = true,
		)

		assertTrue(turning.isCurlUnfolding)
		assertEquals(false, revealed.isCurlUnfolding)
	}

	@Test
	fun `horizontal page curl follows vertical drag origin`() {
		val top = curl(progress = 0.5f, touchFraction = Offset(0.75f, 0.15f))
		val middle = curl(progress = 0.5f, touchFraction = Offset(0.75f, 0.5f))
		val bottom = curl(progress = 0.5f, touchFraction = Offset(0.75f, 0.85f))

		assertTrue(top.topCurlOffset.x < top.bottomCurlOffset.x)
		assertEquals(middle.topCurlOffset.x, middle.bottomCurlOffset.x, 0.001f)
		assertTrue(bottom.topCurlOffset.x > bottom.bottomCurlOffset.x)
	}

	@Test
	fun `backward page curl mirrors vertical drag origin`() {
		val top = curl(progress = 0.5f, touchFraction = Offset(0.25f, 0.15f), isReversed = true)
		val middle = curl(progress = 0.5f, touchFraction = Offset(0.25f, 0.5f), isReversed = true)
		val bottom = curl(progress = 0.5f, touchFraction = Offset(0.25f, 0.85f), isReversed = true)

		assertTrue(top.topCurlOffset.x > top.bottomCurlOffset.x)
		assertEquals(middle.topCurlOffset.x, middle.bottomCurlOffset.x, 0.001f)
		assertTrue(bottom.topCurlOffset.x < bottom.bottomCurlOffset.x)
	}

	@Test
	fun `unfolding page curl aligns the entering edge with drag origin`() {
		val foldingBottom = curl(progress = 0.5f, touchFraction = Offset(0.75f, 0.85f))
		val unfoldingBottom = curl(
			progress = 0.5f,
			touchFraction = Offset(0.75f, 0.85f),
			isCurlUnfolding = true,
		)
		val reversedUnfoldingBottom = curl(
			progress = 0.5f,
			touchFraction = Offset(0.25f, 0.85f),
			isReversed = true,
			isCurlUnfolding = true,
		)

		assertTrue(foldingBottom.topCurlOffset.x > foldingBottom.bottomCurlOffset.x)
		assertTrue(unfoldingBottom.topCurlOffset.x < unfoldingBottom.bottomCurlOffset.x)
		assertTrue(reversedUnfoldingBottom.topCurlOffset.x > reversedUnfoldingBottom.bottomCurlOffset.x)
	}

	@Test
	fun `vertical page curl follows horizontal drag origin`() {
		val left = curl(
			progress = 0.5f,
			touchFraction = Offset(0.15f, 0.75f),
			isVertical = true,
		)
		val middle = curl(
			progress = 0.5f,
			touchFraction = Offset(0.5f, 0.75f),
			isVertical = true,
		)
		val right = curl(
			progress = 0.5f,
			touchFraction = Offset(0.85f, 0.75f),
			isVertical = true,
		)

		assertTrue(left.topCurlOffset.y < left.bottomCurlOffset.y)
		assertEquals(middle.topCurlOffset.y, middle.bottomCurlOffset.y, 0.001f)
		assertTrue(right.topCurlOffset.y > right.bottomCurlOffset.y)
	}

	@Test
	fun `vertical unfolding curl reverses horizontal corner bias`() {
		val foldingLeft = curl(
			progress = 0.5f,
			touchFraction = Offset(0.15f, 0.75f),
			isVertical = true,
		)
		val unfoldingLeft = curl(
			progress = 0.5f,
			touchFraction = Offset(0.15f, 0.75f),
			isVertical = true,
			isCurlUnfolding = true,
		)

		assertTrue(foldingLeft.topCurlOffset.y < foldingLeft.bottomCurlOffset.y)
		assertTrue(unfoldingLeft.topCurlOffset.y > unfoldingLeft.bottomCurlOffset.y)
	}

	@Test
	fun `horizontal page curl center moves monotonically in both directions`() {
		listOf(0.15f, 0.5f, 0.85f).forEach { startY ->
			listOf(false, true).forEach { isReversed ->
				val centers = (0..100).map { step ->
					val geometry = curl(
						progress = step / 100f,
						touchFraction = Offset(0.75f, startY),
						isReversed = isReversed,
					)
					(geometry.topCurlOffset.x + geometry.bottomCurlOffset.x) / 2f
				}
				centers.zipWithNext().forEach { (current, next) ->
					val movesForward = if (isReversed) next >= current - 0.001f else next <= current + 0.001f
					assertTrue(
						movesForward,
						"startY=$startY reversed=$isReversed current=$current next=$next",
					)
				}
			}
		}
	}

	@Test
	fun `vertical page curl center moves monotonically for every drag origin`() {
		listOf(0.15f, 0.5f, 0.85f).forEach { startX ->
			val centers = (0..100).map { step ->
				val geometry = curl(
					progress = step / 100f,
					touchFraction = Offset(startX, 0.75f),
					isVertical = true,
				)
				(geometry.topCurlOffset.y + geometry.bottomCurlOffset.y) / 2f
			}
			centers.zipWithNext().forEach { (current, next) ->
				assertTrue(
					next <= current + 0.001f,
					"startX=$startX current=$current next=$next",
				)
			}
		}
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
	fun `page curl unfolding follows target page with drag fallback`() {
		assertTrue(
			resolvePageCurlUnfolding(
				settledPage = 4,
				targetPage = 3,
				horizontalDragFraction = 0f,
				isReadingReversed = false,
			),
		)
		assertEquals(
			false,
			resolvePageCurlUnfolding(
				settledPage = 4,
				targetPage = 5,
				horizontalDragFraction = 0f,
				isReadingReversed = false,
			),
		)
		assertTrue(
			resolvePageCurlUnfolding(
				settledPage = 4,
				targetPage = 4,
				horizontalDragFraction = 0.1f,
				isReadingReversed = false,
			),
		)
		assertTrue(
			resolvePageCurlUnfolding(
				settledPage = 4,
				targetPage = 4,
				horizontalDragFraction = -0.1f,
				isReadingReversed = true,
			),
		)
		assertTrue(
			resolvePageCurlUnfolding(
				settledPage = 4,
				targetPage = 4,
				horizontalDragFraction = 0f,
				isReadingReversed = false,
				verticalDragFraction = 0.1f,
				isVertical = true,
			),
		)
	}

	@Test
	fun `horizontal curl starts from selected page edge`() {
		assertEquals(
			Offset(1f, 0.85f),
			resolvePageCurlStartFraction(
				downFraction = Offset(0.62f, 0.85f),
				isVertical = false,
				curlFromStart = false,
			),
		)
		assertEquals(
			Offset(0f, 0.15f),
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
		isCurlUnfolding: Boolean = false,
	) = resolveComposeReaderPageTransform(
		animation = animation,
		pageOffset = pageOffset,
		isVertical = isVertical,
		isReversed = isReversed,
		navigationProgress = navigationProgress,
		isSettledPage = isSettledPage,
		isIncomingPage = isIncomingPage,
		isCurlUnfolding = isCurlUnfolding,
	)

	private fun curl(
		progress: Float,
		touchFraction: Offset = Offset(0.75f, 0.85f),
		isVertical: Boolean = false,
		isReversed: Boolean = false,
		isCurlUnfolding: Boolean = false,
	) = calculatePageCurlGeometry(
		size = Size(1000f, 1600f),
		progress = progress,
		touchFraction = touchFraction,
		isVertical = isVertical,
		isReversed = isReversed,
		isCurlUnfolding = isCurlUnfolding,
	)
}
