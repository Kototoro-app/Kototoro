package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NovelChapterDirectoryTest {
    @Test
    fun `reversed filtered rows retain the host chapter index`() {
        val chapters = listOf(
            NovelChapterDirectoryEntry(10, "Arrival"),
            NovelChapterDirectoryEntry(20, "Departure", searchAliases = listOf("English")),
            NovelChapterDirectoryEntry(30, "PART two"),
        )
        val rows = buildNovelChapterDirectoryItems(chapters, true, "part")
            .filterIsInstance<NovelChapterDirectoryItem.Chapter>()
        assertEquals(listOf(2, 1), rows.map { it.originalIndex })
        assertEquals(listOf(30L, 20L), rows.map { it.chapter.id })
        assertEquals(listOf(1), buildNovelChapterDirectoryItems(chapters, false, "ENGLISH")
            .filterIsInstance<NovelChapterDirectoryItem.Chapter>().map { it.originalIndex })
    }

    @Test
    fun `group boundaries reset the volume and repeated headers have unique keys`() {
        val chapters = listOf(
            NovelChapterDirectoryEntry(1, "First", 1, "A"),
            NovelChapterDirectoryEntry(2, "Second", 1, "B"),
            NovelChapterDirectoryEntry(3, "Third", 1, "A"),
            NovelChapterDirectoryEntry(3, "Fourth", 2, "A"),
        )
        val items = buildNovelChapterDirectoryItems(chapters, false, "")
        assertEquals(listOf("A", "Volume 1", "B", "Volume 1", "A", "Volume 1", "Volume 2"),
            items.filterIsInstance<NovelChapterDirectoryItem.Header>().map { it.title })
        assertEquals(items.size, items.map { it.key }.distinct().size)
        assertEquals(8, novelDirectoryPositionForCurrent(items, 2))
        val reversed = buildNovelChapterDirectoryItems(chapters, true, "")
        assertEquals(listOf(3, 2, 1, 0), reversed.filterIsInstance<NovelChapterDirectoryItem.Chapter>()
            .map { it.originalIndex })
    }

    @Test
    fun `volume zero stays ungrouped and the host formats volume titles`() {
        val chapters = listOf(NovelChapterDirectoryEntry(1, null), NovelChapterDirectoryEntry(2, "Second", 2))
        val items = buildNovelChapterDirectoryItems(chapters, false, "  ") { "第 $it 卷" }
        assertEquals(listOf("第 2 卷"), items.filterIsInstance<NovelChapterDirectoryItem.Header>().map { it.title })
        assertEquals(0, novelDirectoryPositionForCurrent(items, 0))
        assertEquals(2, novelDirectoryPositionForCurrent(items, 1))
    }

    @Test
    fun `filtered missing current rows and empty lists have no locate target`() {
        assertEquals(-1, novelDirectoryPositionForCurrent(emptyList(), 0))
        val items = buildNovelChapterDirectoryItems(listOf(NovelChapterDirectoryEntry(1, "First")), false, "missing")
        assertTrue(items.isEmpty())
        assertEquals(-1, novelDirectoryPositionForCurrent(items, 0))
    }

    @Test
    fun `read current and unread states use the original order`() {
        assertEquals(NovelChapterReadState.READ, novelChapterReadState(0, 2))
        assertEquals(NovelChapterReadState.CURRENT, novelChapterReadState(2, 2))
        assertEquals(NovelChapterReadState.UNREAD, novelChapterReadState(3, 2))
        assertEquals(NovelChapterReadState.UNREAD, novelChapterReadState(0, -1))
    }
}
