package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceChapterNavigationTest {
    private val source = SourceRef("fixture", "zh", "MANGA")
    private fun chapter(id: Long, branch: String? = null, sourceName: String = source.name) =
        SourceChapter(id, "$id", id.toFloat(), 0, "/$id", branch, 0, branch, source.copy(name = sourceName))

    @Test
    fun `adjacent chapters preserve supplied order and skip other branches and sources`() {
        val chapters = listOf(chapter(3), chapter(8, "other"), chapter(9, sourceName = "other"), chapter(1), chapter(2))
        assertEquals(1L, SourceChapterNavigation.adjacent(chapters, 3, true)?.id)
        assertEquals(3L, SourceChapterNavigation.adjacent(chapters, 1, false)?.id)
        assertEquals(2L, SourceChapterNavigation.adjacent(chapters, 1, true)?.id)
        assertNull(SourceChapterNavigation.adjacent(chapters, 8, true))
        assertNull(SourceChapterNavigation.adjacent(chapters, 9, false))
    }

    @Test
    fun `missing current chapter and real branch boundaries have no adjacent chapter`() {
        val chapters = listOf(chapter(1, "team"), chapter(2, "team"), chapter(3))
        assertNull(SourceChapterNavigation.adjacent(chapters, 1, false))
        assertNull(SourceChapterNavigation.adjacent(chapters, 2, true))
        assertNull(SourceChapterNavigation.adjacent(chapters, 99, true))
        assertNull(SourceChapterNavigation.adjacent(emptyList(), 1, true))
    }
}
