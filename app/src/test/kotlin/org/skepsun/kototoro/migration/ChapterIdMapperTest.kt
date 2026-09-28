package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.migration.domain.ChapterIdMapper
import org.skepsun.kototoro.parsers.model.ContentChapter

internal fun chapter(id: Long, number: Float, volume: Int = 0, branch: String? = null) = ContentChapter(
    id = id,
    title = "Ch $number",
    number = number,
    volume = volume,
    url = "/c/$id",
    scanlator = null,
    uploadDate = 0L,
    branch = branch,
    source = TestContentSource,
)

class ChapterIdMapperTest {
    @Test
    fun `maps by volume and number first`() {
        val old = listOf(chapter(1, 1f), chapter(2, 2f), chapter(3, 3f))
        val new = listOf(chapter(10, 0.5f), chapter(11, 1f), chapter(12, 2f), chapter(13, 3f))
        assertEquals(mapOf(1L to 11L, 2L to 12L, 3L to 13L), ChapterIdMapper.map(old, new))
    }

    @Test
    fun `falls back to index and clamps to the last chapter`() {
        val old = listOf(chapter(1, 0f), chapter(2, 0f), chapter(3, 0f))
        val new = listOf(chapter(10, 0f), chapter(11, 0f))
        assertEquals(mapOf(1L to 10L, 2L to 11L, 3L to 11L), ChapterIdMapper.map(old, new))
    }

    @Test
    fun `uses the same branch when the new content has it`() {
        val old = listOf(chapter(1, 1f, branch = "EN"))
        val new = listOf(chapter(10, 1f, branch = "RU"), chapter(20, 1f, branch = "EN"))
        assertEquals(mapOf(1L to 20L), ChapterIdMapper.map(old, new))
    }

    @Test
    fun `empty new chapters produce empty map`() {
        assertTrue(ChapterIdMapper.map(listOf(chapter(1, 1f)), emptyList()).isEmpty())
    }

    @Test
    fun `index lookup picks from the largest branch`() {
        val new = listOf(chapter(10, 1f, branch = "A"), chapter(20, 1f, branch = "B"), chapter(21, 2f, branch = "B"))
        assertEquals(21L, ChapterIdMapper.idAtIndex(new, 5))
    }
}
