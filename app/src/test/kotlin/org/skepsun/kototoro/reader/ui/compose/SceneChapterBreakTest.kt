package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

class SceneChapterBreakTest {

    private val source = object : ContentSource {
        override val name = "test"
        override val locale = "en"
        override val contentType = ContentType.MANGA
    }

    private fun page(id: Long, chapterId: Long, index: Int) = ReaderPage(
        id = id,
        url = "https://example.test/$id",
        preview = null,
        headers = null,
        chapterId = chapterId,
        index = index,
        source = source,
    )

    @Test
    fun `scene lists get a break between chapters and after the last page`() {
        val pages = listOf(page(1, 1, 0), page(2, 1, 1), page(3, 2, 0), page(4, 2, 1))
        val shown = withSceneChapterBreaks(pages, enabled = true)
        assertEquals(
            listOf(false, false, true, false, false, true),
            shown.map { it.isChapterBreak },
        )
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L), shown.map { it.chapterId })
    }

    @Test
    fun `the trailing break is the boundary break once the next chapter loads`() {
        val before = withSceneChapterBreaks(listOf(page(1, 1, 0), page(2, 1, 1)), enabled = true)
        val after = withSceneChapterBreaks(listOf(page(1, 1, 0), page(2, 1, 1), page(3, 2, 0)), enabled = true)
        assertEquals(before.last().readerKey, after[2].readerKey)
    }

    @Test
    fun `disabled leaves the list alone`() {
        val pages = listOf(page(1, 1, 0), page(2, 2, 0))
        assertSame(pages, withSceneChapterBreaks(pages, enabled = false))
    }

    @Test
    fun `positions of the unmodified list map past inserted breaks`() {
        val pages = listOf(page(1, 1, 0), page(2, 2, 0), page(3, 2, 1))
        val shown = withSceneChapterBreaks(pages, enabled = true)
        assertEquals(2, sceneShownPosition(1, pages, shown))
        assertEquals(3, sceneShownPosition(2, pages, shown))
    }

    @Test
    fun `a break reports as the last real page before it`() {
        val pages = listOf(page(1, 1, 0), page(2, 2, 0))
        val shown = withSceneChapterBreaks(pages, enabled = true)
        val breakKey = shown[1].readerKey
        assertEquals(shown[0].readerKey, sceneReportedKey(breakKey, shown))
        assertEquals(shown[2].readerKey, sceneReportedKey(shown[2].readerKey, shown))
    }

    // Reading order 1 -> 2 -> 3; chapter 2 has four pages.
    private val previous = mapOf(2L to 1L, 3L to 2L)
    private val counts = mapOf(1L to 3, 2L to 4, 3L to 2)

    private fun insert(pages: List<ReaderPage>) = insertChapterBreaks(
        pages,
        previousChapterIdOf = { previous[it] },
        pageCountOf = { counts[it] ?: 0 },
    )

    private fun chapter(chapterId: Long, range: IntRange, idBase: Long = chapterId * 100) =
        range.map { page(idBase + it, chapterId, it) }

    @Test
    fun `a window of one chapter gets breaks before and after even when the neighbours are not loaded`() {
        val shown = insert(chapter(2, 0..3))
        assertEquals(listOf(true, false, false, false, false, true), shown.map { it.isChapterBreak })
        assertEquals(1L, shown.first().chapterId) // follows chapter 1
        assertEquals(2L, shown.last().chapterId) // follows chapter 2
    }

    @Test
    fun `the first chapter has no break in front`() {
        val shown = insert(chapter(1, 0..2))
        assertEquals(listOf(false, false, false, true), shown.map { it.isChapterBreak })
    }

    @Test
    fun `the last chapter ends with a break that says so`() {
        val shown = insert(chapter(3, 0..1))
        assertEquals(listOf(true, false, false, true), shown.map { it.isChapterBreak })
        assertEquals(3L, shown.last().chapterId)
    }

    @Test
    fun `a window cut inside a neighbour gets no break at the cut`() {
        // The tail of chapter 1 (its last page only), all of chapter 2, the head of chapter 3 (one of two pages).
        val window = chapter(1, 2..2) + chapter(2, 0..3) + chapter(3, 0..0)
        val shown = insert(window)
        val kinds = shown.map { if (it.isChapterBreak) "B${it.chapterId}" else "${it.chapterId}:${it.index}" }
        assertEquals(listOf("1:2", "B1", "2:0", "2:1", "2:2", "2:3", "B2", "3:0"), kinds)
    }

    @Test
    fun `a window starting at a chapter's first page gets a break in front`() {
        // Chapter 1 fully inside the window (3 pages), all of chapter 2.
        val shown = insert(chapter(1, 0..2) + chapter(2, 0..3))
        val kinds = shown.map { if (it.isChapterBreak) "B${it.chapterId}" else "${it.chapterId}:${it.index}" }
        assertEquals(listOf("1:0", "1:1", "1:2", "B1", "2:0", "2:1", "2:2", "2:3", "B2"), kinds)
    }

    @Test
    fun `a split wide page keeps one break after its second half`() {
        val wide = page(2, 1, 1)
        val pages = listOf(
            page(1, 1, 0),
            wide.copy(split = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.LEFT),
            wide.copy(split = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.RIGHT),
            page(3, 1, 2),
        )
        val shown = insert(pages)
        assertEquals(listOf(false, false, false, false, true), shown.map { it.isChapterBreak })
    }

    @Test
    fun `the break page in front of a chapter keeps its key when the previous chapter loads`() {
        val alone = insert(chapter(2, 0..3))
        val withPrevious = insert(chapter(1, 0..2) + chapter(2, 0..3))
        val boundary = withPrevious.first { it.isChapterBreak && it.chapterId == 1L }
        assertEquals(alone.first().readerKey, boundary.readerKey)
    }

    @Test
    fun `the break page after a chapter keeps its key when the next chapter loads`() {
        val alone = insert(chapter(2, 0..3))
        val withNext = insert(chapter(2, 0..3) + chapter(3, 0..1))
        val boundary = withNext.first { it.isChapterBreak && it.chapterId == 2L }
        assertEquals(alone.last().readerKey, boundary.readerKey)
    }

    @Test
    fun `positions of the real pages stay findable by chapter and index`() {
        val shown = insert(chapter(2, 0..3))
        assertEquals(1, shown.indexOfFirst { it.chapterId == 2L && it.index == 0 })
    }
}
