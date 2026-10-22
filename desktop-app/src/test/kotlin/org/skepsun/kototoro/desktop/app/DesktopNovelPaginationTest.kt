package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Whole-block pages: a paragraph is never cut, and no page can be empty, so flipping always advances. */
class DesktopNovelPaginationTest {
    @Test
    fun `an empty or unmeasurable chapter has no pages`() {
        assertEquals(emptyList<IntRange>(), desktopNovelPages(emptyList(), 400))
        assertEquals(emptyList<IntRange>(), desktopNovelPages(listOf(10, 10), 0))
        assertEquals(emptyList<IntRange>(), desktopNovelPages(listOf(10, 10), -1))
    }

    @Test
    fun `blocks are grouped while they still fit the page`() {
        assertEquals(listOf(0..2), desktopNovelPages(listOf(10, 10, 10), 30))
        assertEquals(listOf(0..1, 2..2), desktopNovelPages(listOf(10, 10, 10), 20))
        assertEquals(listOf(0..0, 1..1, 2..2), desktopNovelPages(listOf(10, 10, 10), 15))
        // The gap between blocks counts: a small tail block joins the page it still fits on.
        assertEquals(listOf(0..1, 2..3), desktopNovelPages(listOf(10, 10, 10, 4), 25))
    }

    @Test
    fun `a block taller than the page gets its own page`() {
        assertEquals(listOf(0..0, 1..1, 2..2), desktopNovelPages(listOf(10, 80, 10), 30))
        // Two oversized blocks in a row are two pages, not one page that hides one of them.
        assertEquals(listOf(0..0, 1..1), desktopNovelPages(listOf(80, 80), 30))
    }

    @Test
    fun `every block belongs to exactly one page`() {
        val pages = desktopNovelPages(listOf(12, 7, 40, 3, 3, 3, 100), 50)
        val placed = pages.flatMap { it.toList() }
        assertEquals((0 until 7).toList(), placed)
        assertEquals(0, desktopNovelPageOf(pages, 0))
        assertEquals(pages.lastIndex, desktopNovelPageOf(pages, 6))
        // A block outside the measured chapter is not on any page, so a stale slider cannot open one.
        assertEquals(-1, desktopNovelPageOf(pages, 7))
        assertEquals(-1, desktopNovelPageOf(emptyList(), 0))
    }

    @Test
    fun `pager keeps the anchored block and stops at the ends`() {
        val pager = DesktopNovelPagerState(startBlock = 3)
        pager.rebind(listOf(0..1, 2..3, 4..5))
        // The anchor decides the first page, so entering paged mode continues the paragraph being read.
        assertEquals(1, pager.page)
        assertEquals(2..3, pager.range)
        assertTrue(pager.next())
        assertEquals(4..5, pager.range)
        assertEquals(4, pager.anchorBlock)
        assertFalse(pager.next())
        assertTrue(pager.previous())
        assertEquals(2..3, pager.range)
        assertTrue(pager.previous())
        assertEquals(0..1, pager.range)
        assertFalse(pager.previous())
    }

    @Test
    fun `a reflow keeps the same text on screen`() {
        val pager = DesktopNovelPagerState(startBlock = 0)
        pager.rebind(listOf(0..3, 4..7))
        pager.next()
        assertEquals(4..7, pager.range)
        // A narrower window measures more pages: block 5 now sits on the second of three.
        pager.rebind(listOf(0..1, 2..5, 6..7))
        assertEquals(2..5, pager.range)
        assertEquals(2, pager.anchorBlock)
        // Shrinking to one page clamps instead of leaving the reader on a page that no longer exists.
        pager.rebind(listOf(0..7))
        assertEquals(0, pager.page)
        assertEquals(0..7, pager.range)
    }

    @Test
    fun `jumping to a block and back to an empty chapter is safe`() {
        val pager = DesktopNovelPagerState(startBlock = 0)
        assertTrue(pager.isEmpty)
        assertFalse(pager.next())
        assertFalse(pager.previous())
        assertFalse(pager.jumpToBlock(2))
        pager.rebind(listOf(0..2, 3..5))
        assertTrue(pager.jumpToBlock(4))
        assertEquals(3..5, pager.range)
        assertFalse(pager.jumpToBlock(9))
        pager.rebind(emptyList())
        assertTrue(pager.isEmpty)
        assertEquals(null, pager.range)
        assertFalse(pager.next())
    }
}
