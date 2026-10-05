package org.skepsun.kototoro.core.ui.preview

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TabletPreviewChaptersTest {
    private data class Chapter(val number: Float, val branch: String? = null, val date: Long = 0)

    private fun groups(chapters: List<Chapter>) = resolveTabletPreviewChapters(
        chapters, { it.branch }, { it.number }, { it.date },
    )

    @Test
    fun `empty chapters have no read target`() {
        val result = groups(emptyList())
        assertNull(result.firstChapter)
        assertTrue(result.isSingleGroup)
        assertTrue(result.earliest.isEmpty())
    }

    @Test
    fun `short primary branch remains one section and excludes other translations`() {
        val chapters = (1..4).flatMap { listOf(Chapter(it.toFloat(), "A"), Chapter(it.toFloat(), "B")) }
        val result = groups(chapters)
        assertEquals(listOf(1f, 2f, 3f, 4f), result.earliest.map { it.number })
        assertTrue(result.earliest.all { it.branch == "A" })
        assertTrue(result.latest.isEmpty())
        assertTrue(result.isSingleGroup)
    }

    @Test
    fun `ascending and descending sources expose the same latest and earliest entries`() {
        val chapters = (1..10).map { Chapter(it.toFloat()) }
        for (sourceOrder in listOf(chapters, chapters.reversed())) {
            val result = groups(sourceOrder)
            assertEquals(listOf(1f, 2f, 3f), result.earliest.map { it.number })
            assertEquals(listOf(10f, 9f, 8f), result.latest.map { it.number })
            assertEquals(1f, result.firstChapter!!.number)
            assertFalse(result.isSingleGroup)
        }
    }

    @Test
    fun `unnumbered sources use upload date to recover order`() {
        val result = groups((10L downTo 1L).map { Chapter(0f, date = it) })
        assertEquals(listOf(1L, 2L, 3L), result.earliest.map { it.date })
        assertEquals(listOf(10L, 9L, 8L), result.latest.map { it.date })
    }

    @Test
    fun `unknown initial branch preserves source order and does not drop chapters`() {
        val chapters = listOf(Chapter(1f)) + (2..5).map { Chapter(it.toFloat(), "B") }
        assertEquals(chapters, groups(chapters).earliest)
    }
}
