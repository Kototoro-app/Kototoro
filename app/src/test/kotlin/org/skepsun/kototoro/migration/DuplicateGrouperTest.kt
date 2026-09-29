package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.DuplicateGrouper

class DuplicateGrouperTest {

    private fun row(
        id: Long,
        title: String,
        alt: String? = null,
        type: String? = "MANGA",
        source: String = "S$id",
        chapters: Int = 10,
        readTo: Float? = null,
        percent: Float? = null,
    ) = LibraryRow(
        id = id, title = title, altTitles = alt, source = source, contentType = type, coverUrl = "",
        chaptersCount = chapters, trackResult = null, trackCheckTime = null, trackError = null,
        historyPercent = percent, historyChapterNumber = readTo,
    )

    @Test
    fun `traditional and simplified titles land in one group`() {
        val groups = DuplicateGrouper.group(
            listOf(row(1, "间谍过家家"), row(2, "間諜過家家"), row(3, "其他作品")),
            unhealthySources = emptySet(),
            ignoredKeys = emptySet(),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L), groups.single().entries.map { it.row.id }.toSet())
    }

    @Test
    fun `alternative titles chain entries together`() {
        val groups = DuplicateGrouper.group(
            listOf(
                row(1, "Sousou no Frieren"),
                row(2, "葬送的芙莉莲", alt = "Sousou no Frieren"),
                row(3, "葬送的芙莉蓮"),
            ),
            unhealthySources = emptySet(),
            ignoredKeys = emptySet(),
        )
        assertEquals(setOf(1L, 2L, 3L), groups.single().entries.map { it.row.id }.toSet())
    }

    @Test
    fun `different content families never group`() {
        val groups = DuplicateGrouper.group(
            listOf(row(1, "Frieren"), row(2, "Frieren", type = "VIDEO")),
            unhealthySources = emptySet(),
            ignoredKeys = emptySet(),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `recommendation prefers healthy source then reading progress then chapters`() {
        val rows = listOf(
            row(1, "Frieren", source = "DEAD", readTo = 90f, chapters = 120),
            row(2, "Frieren", readTo = 40f, chapters = 100),
            row(3, "Frieren", readTo = null, chapters = 130),
        )
        val group = DuplicateGrouper.group(rows, unhealthySources = setOf("DEAD"), ignoredKeys = emptySet()).single()
        assertEquals(2L, group.recommendedId)
        assertTrue(group.entries.first { it.row.id == 1L }.sourceBroken)
    }

    @Test
    fun `ignored groups are hidden`() {
        val rows = listOf(row(1, "Frieren"), row(2, "Frieren"))
        val key = DuplicateGrouper.group(rows, emptySet(), emptySet()).single().key
        assertTrue(DuplicateGrouper.group(rows, emptySet(), setOf(key)).isEmpty())
    }

    @Test
    fun `further progress compares chapter number`() {
        assertTrue(DuplicateGrouper.isFurther(row(1, "a", readTo = 10f), row(2, "a", readTo = 3f)))
        assertFalse(DuplicateGrouper.isFurther(row(1, "a", readTo = null), row(2, "a", readTo = 3f)))
    }

    @Test
    fun `further progress falls back to percent when chapter numbers are unknown`() {
        assertTrue(DuplicateGrouper.isFurther(row(1, "a", percent = 0.8f), row(2, "a", percent = 0.1f)))
        assertFalse(DuplicateGrouper.isFurther(row(1, "a", percent = 0.8f), row(2, "a", readTo = 1f)))
    }
}
