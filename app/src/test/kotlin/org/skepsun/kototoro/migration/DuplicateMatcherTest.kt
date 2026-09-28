package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.DuplicateMatcher
import org.skepsun.kototoro.parsers.model.ContentType

class DuplicateMatcherTest {
    private fun row(id: Long, title: String, alt: String? = null, type: String? = "MANGA") = LibraryRow(
        id = id, title = title, altTitles = alt, source = "S", contentType = type, coverUrl = "",
        chaptersCount = 0, trackResult = null, trackCheckTime = null, trackError = null,
        historyPercent = null, historyChapterNumber = null,
    )

    @Test
    fun `matches normalized title`() {
        val result = DuplicateMatcher.find(
            id = 99, title = "ＯＮＥ ＰＩＥＣＥ", altTitles = emptySet(), family = ContentType.MANGA,
            library = listOf(row(1, "One Piece"), row(2, "Naruto")),
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `matches through alternative titles on either side`() {
        val result = DuplicateMatcher.find(
            id = 99, title = "葬送的芙莉莲", altTitles = setOf("Sousou no Frieren"), family = ContentType.MANGA,
            library = listOf(row(1, "Sousou no Frieren"), row(2, "フリーレン", alt = "葬送的芙莉莲")),
        )
        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun `different content family is ignored`() {
        val result = DuplicateMatcher.find(
            id = 99, title = "Frieren", altTitles = emptySet(), family = ContentType.MANGA,
            library = listOf(row(1, "Frieren", type = "VIDEO")),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `self is excluded`() {
        val result = DuplicateMatcher.find(
            id = 1, title = "Frieren", altTitles = emptySet(), family = ContentType.MANGA,
            library = listOf(row(1, "Frieren")),
        )
        assertTrue(result.isEmpty())
    }
}
