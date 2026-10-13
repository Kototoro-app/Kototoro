package org.skepsun.kototoro.reader.ui.pager.doublepage

import android.util.Log

internal data class DoublePageViewport(
    val lowerPos: Int,
    val upperPos: Int,
)

/**
 * Represents a single layout slot in the double-page reader:
 * either a single page or a pair of pages (spread).
 */
internal data class PageSpreadSlot(
    val firstPageIndex: Int,
    val secondPageIndex: Int? = null // null means it sits alone
)

/**
 * Computes the list of layout slots (spreads) for the chapter,
 * ensuring wide pages sit alone, pairing restarts fresh after a spread,
 * and single pages are isolated.
 */
internal fun calculateSpreadSlots(
    itemCount: Int,
    isWidePage: (Int) -> Boolean
): List<PageSpreadSlot> {
    val slots = mutableListOf<PageSpreadSlot>()
    var i = 0
    while (i < itemCount) {
        // Condition 1: If the current page is wide, it sits completely alone
        if (isWidePage(i)) {
            slots.add(PageSpreadSlot(firstPageIndex = i, secondPageIndex = null))
            i++
            continue
        }

        // Check if there is a next page available to form a spread
        if (i + 1 < itemCount) {
            val nextPageIndex = i + 1
            
            // Condition 1 (cont.): If the next page is wide, current page must sit alone first
            if (isWidePage(nextPageIndex)) {
                slots.add(PageSpreadSlot(firstPageIndex = i, secondPageIndex = null))
                i++
                continue
            }

            // Pair normal pages together (RTL spread: right page `i`, left page `i + 1`)
            slots.add(PageSpreadSlot(firstPageIndex = i, secondPageIndex = nextPageIndex))
            i += 2 // Restart pairing fresh after this spread
        } else {
            // Condition 3: Leftover single page at the end sits alone
            slots.add(PageSpreadSlot(firstPageIndex = i, secondPageIndex = null))
            i++
        }
    }
    return slots
}

internal fun resolveCurrentDoublePageViewport(
    firstCompletelyVisibleItemPosition: Int,
    firstVisibleItemPosition: Int,
    itemCount: Int,
    isWidePage: (Int) -> Boolean = { false }
): DoublePageViewport? {
    if (itemCount <= 0) {
        Log.d(LOG_TAG, "resolveCurrentDoublePageViewport: empty itemCount=$itemCount")
        return null
    }
    val lastIndex = itemCount - 1
    val anchor = when {
        firstCompletelyVisibleItemPosition != -1 -> firstCompletelyVisibleItemPosition
        firstVisibleItemPosition != -1 -> firstVisibleItemPosition
        else -> return null
    }.coerceIn(0, lastIndex)

    // Generate dynamic slots based on your rules
    val slots = calculateSpreadSlots(itemCount, isWidePage)
    
    // Find the slot containing our anchor
    val currentSlot = slots.find { slot ->
        slot.firstPageIndex == anchor || slot.secondPageIndex == anchor
    } ?: slots.first()

    val lowerPos = currentSlot.firstPageIndex
    val upperPos = currentSlot.secondPageIndex ?: currentSlot.firstPageIndex

    val viewport = DoublePageViewport(
        lowerPos = lowerPos,
        upperPos = upperPos,
    )
    
    Log.d(
        LOG_TAG,
        "resolveCurrentDoublePageViewport: anchor=$anchor, viewport=$viewport",
    )
    return viewport
}

private const val LOG_TAG = "ReaderDebug"
