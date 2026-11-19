package org.skepsun.kototoro.reader.ui.compose

import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

internal data class DoublePageDisplayItem(
    val page: ReaderPage?,
    val originalPosition: Int,
    val isChapterLeadingSpacer: Boolean = false,
)

internal fun buildDoublePageDisplayItems(
    pages: List<ReaderPage>,
    coverPage: Boolean = false,
): List<DoublePageDisplayItem> {
    if (pages.isEmpty()) return emptyList()
    return buildList {
        var currentChapterId = pages.first().chapterId
        // A break page in front of the window is not a chapter's first page; the real first page that follows
        // gets its own spacer through the chapter change below.
        if (!pages.first().isChapterBreak) addDoublePageChapterLeadingSpacer(pages.first(), coverPage)
        pages.forEachIndexed { position, page ->
            if (page.chapterId != currentChapterId) {
                if (size % 2 != 0) add(DoublePageDisplayItem(page = null, originalPosition = -1))
                currentChapterId = page.chapterId
                addDoublePageChapterLeadingSpacer(page, coverPage)
            }
            add(DoublePageDisplayItem(page = page, originalPosition = position))
        }
    }
}

private fun MutableList<DoublePageDisplayItem>.addDoublePageChapterLeadingSpacer(
    firstPage: ReaderPage,
    coverPage: Boolean,
) {
    val coverOffset = if (coverPage) 1 else 0
    val startsAtFullChapterBoundary = firstPage.index == 0
    val needsSpacer = if (startsAtFullChapterBoundary) {
        coverPage
    } else {
        (firstPage.index + coverOffset) % 2 != 0
    }
    if (needsSpacer) add(DoublePageDisplayItem(page = null, originalPosition = -1, isChapterLeadingSpacer = true))
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

        fun create(
            displayItems: List<DoublePageDisplayItem>,
            widePageIds: Set<Long> = emptySet(),
        ): DoublePageSpreadModel {
            val count = displayItems.size
            if (count <= 0) return DoublePageSpreadModel(emptyList())

            // An unsplit page detected as wide always gets a spread to itself.
            fun isWideSolo(pos: Int): Boolean {
                val page = displayItems.getOrNull(pos)?.page ?: return false
                return page.isChapterBreak || (page.split == ReaderPageSplit.NONE && page.id in widePageIds)
            }

            fun isSplitHalf(pos: Int): Boolean {
                val page = displayItems.getOrNull(pos)?.page ?: return false
                return page.split != ReaderPageSplit.NONE
            }

            // The two halves of one split wide page belong together on a single spread.
            fun isSplitHalfPair(pos: Int): Boolean {
                val first = displayItems.getOrNull(pos)?.page ?: return false
                val second = displayItems.getOrNull(pos + 1)?.page ?: return false
                return first.split != ReaderPageSplit.NONE &&
                    second.split != ReaderPageSplit.NONE &&
                    first.split != second.split &&
                    first.id == second.id && first.chapterId == second.chapterId
            }

            val spreads = buildList {
                var i = 0
                while (i < count) {
                    val current = displayItems[i]
                    val next = displayItems.getOrNull(i + 1)
                    if (current.page == null) {
                        // Leading spacers position a cover/truncated tail on one side; chapter padding
                        // must never become an empty spread or offset the next chapter after a wide page.
                        if (current.isChapterLeadingSpacer && next?.page != null &&
                            !isWideSolo(i + 1) && !isSplitHalfPair(i + 1)
                        ) {
                            add(DoublePageSpread(lowerPosition = i, upperPosition = i + 1))
                            i += 2
                        } else {
                            i++
                        }
                    } else if (isWideSolo(i)) {
                        add(DoublePageSpread(lowerPosition = i, upperPosition = i))
                        i++
                    } else if (isSplitHalfPair(i)) {
                        add(DoublePageSpread(lowerPosition = i, upperPosition = i + 1))
                        i += 2
                    } else if (isSplitHalf(i)) {
                        // Orphan half without its partner (chapter edge): keep it alone.
                        add(DoublePageSpread(lowerPosition = i, upperPosition = i))
                        i++
                    } else if (i + 1 < count) {
                        val isTrailingSpacer = next?.page == null && next?.isChapterLeadingSpacer == false
                        val canPair = isTrailingSpacer || (next?.page?.chapterId == current.page.chapterId &&
                            !isWideSolo(i + 1) && !isSplitHalf(i + 1))
                        if (!canPair) {
                            add(DoublePageSpread(lowerPosition = i, upperPosition = i))
                            i++
                        } else {
                            add(DoublePageSpread(lowerPosition = i, upperPosition = i + 1))
                            i += 2
                        }
                    } else {
                        add(DoublePageSpread(lowerPosition = i, upperPosition = i))
                        i++
                    }
                }
            }
            return DoublePageSpreadModel(spreads)
        }

        @Deprecated("Use overload with displayItems")
        fun create(pageCount: Int): DoublePageSpreadModel {
            val spreads = buildList {
                for (lower in 0 until pageCount step 2) {
                    add(DoublePageSpread(lowerPosition = lower, upperPosition = (lower + 1).coerceAtMost(pageCount - 1)))
                }
            }
            return DoublePageSpreadModel(spreads)
        }
    }

    fun spreadIndexForPage(position: Int): Int {
        if (spreads.isEmpty()) return 0
        val clampedPosition = position.coerceIn(0, spreads.last().upperPosition)
        return spreads.indexOfFirst { clampedPosition <= it.upperPosition }.coerceAtLeast(0)
    }
}

internal fun resolvePageNavigationTarget(
    currentPosition: Int,
    delta: Int,
    pageStep: Int,
): Int = currentPosition + delta * pageStep

/** True when the spread is a single detected-wide page that should use the whole screen width. */
internal fun DoublePageSpread.isFullWidthWidePage(
    displayItems: List<DoublePageDisplayItem>,
    widePageIds: Set<Long>,
): Boolean {
    if (lowerPosition != upperPosition) return false
    val page = displayItems.getOrNull(lowerPosition)?.page ?: return false
    return page.isChapterBreak || (page.split == ReaderPageSplit.NONE && page.id in widePageIds)
}

internal fun resolveDoublePageNavigationTarget(
    displayItems: List<DoublePageDisplayItem>,
    currentPosition: Int,
    delta: Int,
    widePageIds: Set<Long> = emptySet(),
): Int? {
    val currentDisplayPosition = displayItems.indexOfFirst { it.originalPosition == currentPosition }
    if (currentDisplayPosition < 0) return null
    val spreadModel = DoublePageSpreadModel.create(displayItems, widePageIds)
    val currentSpreadIndex = spreadModel.spreadIndexForPage(currentDisplayPosition)
    val targetSpread = spreadModel.spreads.getOrNull(currentSpreadIndex + delta) ?: return null
    val targetPositions = targetSpread.positions.mapNotNull { position ->
        displayItems[position].originalPosition.takeIf { it >= 0 }
    }
    return if (delta < 0) targetPositions.lastOrNull() else targetPositions.firstOrNull()
}

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
