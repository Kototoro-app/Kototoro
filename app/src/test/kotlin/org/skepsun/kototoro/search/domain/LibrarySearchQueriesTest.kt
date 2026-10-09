package org.skepsun.kototoro.search.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType

class LibrarySearchQueriesTest {

    private data object TestSource : ContentSource {
        override val name = "test"
        override val locale = ""
        override val contentType = ContentType.MANGA
    }

    @Test
    fun `filters are kept per page`() {
        val queries = LibrarySearchQueries()

        queries.set(LibrarySearchScope.HISTORY, "  frieren ")

        assertEquals("frieren", queries.query(LibrarySearchScope.HISTORY).value)
        assertEquals("", queries.query(LibrarySearchScope.FAVOURITES).value)
        queries.clear(LibrarySearchScope.HISTORY)
        assertEquals("", queries.query(LibrarySearchScope.HISTORY).value)
    }

    @Test
    fun `an active filter or the last choice opens the page tab`() {
        val queries = LibrarySearchQueries()
        assertFalse(queries.prefersScopedTab(LibrarySearchScope.HISTORY))

        queries.setPrefersScopedTab(LibrarySearchScope.HISTORY, true)
        assertTrue(queries.prefersScopedTab(LibrarySearchScope.HISTORY))
        assertFalse(queries.prefersScopedTab(LibrarySearchScope.FAVOURITES))

        queries.setPrefersScopedTab(LibrarySearchScope.FAVOURITES, false)
        queries.set(LibrarySearchScope.FAVOURITES, "oda")
        assertTrue(queries.prefersScopedTab(LibrarySearchScope.FAVOURITES))
    }

    @Test
    fun `library matching uses titles authors and tags and keeps library order`() {
        val contents = listOf(
            content(3, "Blue Period", tags = setOf("Art")),
            content(2, "One Piece", authors = setOf("Eiichiro Oda")),
            content(1, "Frieren", altTitles = setOf("葬送的芙莉莲")),
        )

        assertEquals(listOf(2L), contents.matchLibraryText("piece oda", 10).map { it.id })
        assertEquals(listOf(3L), contents.matchLibraryText("art", 10).map { it.id })
        assertEquals(listOf(1L), contents.matchLibraryText("芙莉莲", 10).map { it.id })
        assertEquals(listOf(3L, 2L), contents.matchLibraryText("e", 2).map { it.id })
        assertEquals(emptyList<Content>(), contents.matchLibraryText(" ", 10))
    }

    private fun content(
        id: Long,
        title: String,
        authors: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        altTitles: Set<String> = emptySet(),
    ) = Content(
        id = id,
        title = title,
        altTitles = altTitles,
        url = "/work/$id",
        publicUrl = "https://example.test/work/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = tags.mapTo(mutableSetOf()) { ContentTag(it, it, TestSource) },
        state = null,
        authors = authors,
        source = TestSource,
    )
}
