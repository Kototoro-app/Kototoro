package org.skepsun.kototoro.reader.ui.compose

import org.skepsun.kototoro.reader.ui.pager.ReaderPage

internal data class DoublePageDisplayItem(
	val page: ReaderPage?,
	val originalPosition: Int,
)

internal fun buildDoublePageDisplayItems(
	pages: List<ReaderPage>,
	coverPage: Boolean = false,
): List<DoublePageDisplayItem> {
	if (pages.isEmpty()) return emptyList()
	return buildList {
		var currentChapterId = pages.first().chapterId
		if (coverPage) add(DoublePageDisplayItem(page = null, originalPosition = -1))
		pages.forEachIndexed { position, page ->
			if (page.chapterId != currentChapterId) {
				if (size % 2 != 0) add(DoublePageDisplayItem(page = null, originalPosition = -1))
				currentChapterId = page.chapterId
				if (coverPage) add(DoublePageDisplayItem(page = null, originalPosition = -1))
			}
			add(DoublePageDisplayItem(page = page, originalPosition = position))
		}
	}
}

internal data class DoublePageSpread(
	val lowerPosition: Int,
	val upperPosition: Int,
) {
	val positions: IntRange
		get() = lowerPosition..upperPosition

	fun orderedPositions(reverseLayout: Boolean): List<Int> =
		if (reverseLayout) positions.toList().reversed() else positions.toList()
}

internal class DoublePageSpreadModel private constructor(
	val spreads: List<DoublePageSpread>,
) {
	companion object {
		const val SPACER_KEY = Long.MIN_VALUE

		fun create(pageCount: Int): DoublePageSpreadModel {
			require(pageCount >= 0) { "pageCount must not be negative" }
			val spreads = buildList {
				for (lower in 0 until pageCount step PAGES_PER_SPREAD) {
					add(
						DoublePageSpread(
							lowerPosition = lower,
							upperPosition = (lower + 1).coerceAtMost(pageCount - 1),
						),
					)
				}
			}
			return DoublePageSpreadModel(spreads)
		}

		private const val PAGES_PER_SPREAD = 2
	}

	fun spreadIndexForPage(position: Int): Int {
		if (spreads.isEmpty()) return 0
		val clampedPosition = position.coerceIn(0, spreads.last().upperPosition)
		return (clampedPosition / PAGES_PER_SPREAD).coerceIn(spreads.indices)
	}

}

internal fun resolvePageNavigationTarget(
	currentPosition: Int,
	delta: Int,
	pageStep: Int,
): Int = currentPosition + delta * pageStep

internal fun shouldAnimatePageNavigation(
	currentPosition: Int,
	targetPosition: Int,
	smoothRequested: Boolean,
	isAnimationEnabled: Boolean,
): Boolean {
	return smoothRequested && isAnimationEnabled &&
		kotlin.math.abs(currentPosition - targetPosition) < SMOOTH_PAGE_LIMIT
}

internal fun DoublePageSpreadModel.resolveAnchorSpreadIndex(
	pageKeys: List<Long>,
	anchorPageKey: Long,
	fallbackPosition: Int,
): Int {
	val anchorPosition = pageKeys.indexOf(anchorPageKey).takeIf { it >= 0 } ?: fallbackPosition
	return spreadIndexForPage(anchorPosition)
}

private const val SMOOTH_PAGE_LIMIT = 3
