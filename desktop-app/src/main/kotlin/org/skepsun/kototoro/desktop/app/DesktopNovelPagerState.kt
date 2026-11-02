package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The page the Windows novel reader shows in paged mode.
 *
 * Pages depend on the measured viewport, so the layout rebinds them whenever the window or the type changes. The
 * anchor is a block index rather than a page index: a font change re-flows the chapter and the reader keeps showing
 * the text it showed before instead of jumping to a page number that now holds something else.
 */
internal class DesktopNovelPagerState(startBlock: Int) {
    var pages: List<IntRange> by mutableStateOf(emptyList())
        private set
    var page: Int by mutableIntStateOf(0)
        private set

    /** First block of the shown page; the reading position both modes report. */
    var anchorBlock: Int = startBlock
        private set

    val range: IntRange?
        get() = pages.getOrNull(page)

    val isEmpty: Boolean
        get() = pages.isEmpty()

    /** Applies freshly measured pages, keeping the anchored block on screen. */
    fun rebind(computed: List<IntRange>) {
        if (computed == pages) return
        pages = computed
        page = if (computed.isEmpty()) {
            0
        } else {
            desktopNovelPageOf(computed, anchorBlock).takeIf { it >= 0 } ?: page.coerceIn(0, computed.lastIndex)
        }
        range?.let { anchorBlock = it.first }
    }

    fun next(): Boolean = moveTo(page + 1)

    fun previous(): Boolean = moveTo(page - 1)

    /** Shows the page that holds [block]; false when it is not part of the chapter body. */
    fun jumpToBlock(block: Int): Boolean {
        val index = desktopNovelPageOf(pages, block)
        if (index < 0) return false
        page = index
        // The anchor is the page's first block, as after a turn: a reflow then keeps this page's top on screen
        // instead of re-anchoring on [block] and dropping the paragraphs above it.
        anchorBlock = pages[index].first
        return true
    }

    private fun moveTo(index: Int): Boolean {
        if (index !in pages.indices) return false
        page = index
        anchorBlock = pages[index].first
        return true
    }
}
