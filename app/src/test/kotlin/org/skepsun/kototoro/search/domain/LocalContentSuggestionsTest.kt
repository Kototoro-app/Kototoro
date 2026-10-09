package org.skepsun.kototoro.search.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class LocalContentSuggestionsTest {

    private data class TestSource(override val name: String) : ContentSource {
        override val locale = ""
        override val contentType = ContentType.MANGA
    }

    private val sourceA = TestSource("a")
    private val sourceB = TestSource("b")

    @Test
    fun `rows of one work collapse into one suggestion`() {
        val contents = listOf(
            content(1, "Shuujin Tensei", url = ""),
            content(2, "Shuujin Tensei", url = ""),
            content(3, "  shuujin   TENSEI ", url = ""),
            content(4, "Other Work"),
        )

        assertEquals(listOf(1L, 4L), contents.toLocalSuggestions("", 10).map { it.id })
    }

    @Test
    fun `the same title from two sources stays as two works`() {
        val contents = listOf(
            content(1, "One Piece", source = sourceA),
            content(2, "One Piece", source = sourceB),
        )

        assertEquals(listOf(1L, 2L), contents.toLocalSuggestions("one", 10).map { it.id })
    }

    @Test
    fun `the most complete row represents a work`() {
        val contents = listOf(
            content(1, "Frieren", url = "", coverUrl = null),
            content(2, "Frieren", url = "/work/2", coverUrl = null),
            content(3, "Frieren", url = "/work/3", coverUrl = "cover.png"),
            content(4, "Frieren", url = "/work/4", coverUrl = "other.png"),
        )

        assertEquals(listOf(3L), contents.toLocalSuggestions("frieren", 10).map { it.id })
    }

    @Test
    fun `matches rank by prefix then word start then substring then alternative title`() {
        val contents = listOf(
            content(1, "Frieren", altTitles = setOf("Sousou no One")),
            content(2, "Phone Book"),
            content(3, "Big One Piece"),
            content(4, "One Punch Man"),
        )

        assertEquals(listOf(4L, 3L, 2L, 1L), contents.toLocalSuggestions("one", 10).map { it.id })
    }

    @Test
    fun `a whole first word outranks a prefix of a longer word`() {
        val contents = listOf(
            content(1, "Oneppyu"),
            content(2, "Onee-san Is Invading!"),
            content(3, "One-Room Hero"),
            content(4, "One Piece"),
        )

        // "One-Room Hero" and "One Piece" end the word "one"; the other two only start a longer word.
        assertEquals(listOf(3L, 4L, 1L, 2L), contents.toLocalSuggestions("one", 10).map { it.id })
    }

    @Test
    fun `ranking keeps the incoming order within a rank`() {
        val contents = listOf(
            content(1, "One B"),
            content(2, "One A"),
            content(3, "One C"),
        )

        assertEquals(listOf(1L, 2L, 3L), contents.toLocalSuggestions("one", 10).map { it.id })
    }

    @Test
    fun `non latin text matches inside a title`() {
        val contents = listOf(
            content(1, "葬送的芙莉莲"),
            content(2, "芙莉莲外传"),
        )

        assertEquals(listOf(2L, 1L), contents.toLocalSuggestions("芙莉莲", 10).map { it.id })
    }

    @Test
    fun `blank query keeps order and limit applies after de-duplication`() {
        val contents = listOf(
            content(1, "Same"),
            content(2, "Same"),
            content(3, "Second"),
            content(4, "Third"),
        )

        assertEquals(listOf(1L, 3L), contents.toLocalSuggestions("  ", 2).map { it.id })
        assertEquals(emptyList<Content>(), contents.toLocalSuggestions("same", 0))
        assertEquals(emptyList<Content>(), emptyList<Content>().toLocalSuggestions("same", 5))
    }

    private fun content(
        id: Long,
        title: String,
        source: ContentSource = sourceA,
        url: String = "/work/$id",
        coverUrl: String? = null,
        altTitles: Set<String> = emptySet(),
    ) = Content(
        id = id,
        title = title,
        altTitles = altTitles,
        url = url,
        publicUrl = "https://example.test/work/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = coverUrl,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )
}
