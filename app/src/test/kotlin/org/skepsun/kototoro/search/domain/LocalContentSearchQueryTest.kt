package org.skepsun.kototoro.search.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType

class LocalContentSearchQueryTest {

    private data object TestSource : ContentSource {
        override val name = "test"
        override val locale = ""
        override val contentType = ContentType.MANGA
    }

    @Test
    fun `advanced title searches local content without a main query`() {
        val query = LocalContentSearchQuery("", SearchKind.ADVANCED, AdvancedSearchParams(title = "火影"))
        val contents = listOf(content(1, "火影忍者"), content(2, "海贼王"))
        assertEquals(listOf(1L), query.search(contents, 20).map { it.id })
    }

    @Test
    fun `advanced author does not require the author to appear in the title`() {
        val query = LocalContentSearchQuery("", SearchKind.ADVANCED, AdvancedSearchParams(author = "岸本"))
        val contents = listOf(
            content(1, "火影忍者", authors = setOf("岸本齐史")),
            content(2, "未知作者"),
        )
        assertEquals(listOf(1L), query.search(contents, 20).map { it.id })
    }

    @Test
    fun `advanced tags include all requested tags and exclude negative tags`() {
        val query = LocalContentSearchQuery(
            "", SearchKind.ADVANCED, AdvancedSearchParams(tags = " Action, School, -Horror "),
        )
        val contents = listOf(
            content(1, "Match", tags = setOf("action", "school")),
            content(2, "Missing tag", tags = setOf("action")),
            content(3, "Excluded", tags = setOf("action", "school", "horror")),
        )
        assertEquals(listOf(1L), query.search(contents, 20).map { it.id })
    }

    @Test
    fun `negative tags alone are valid search criteria`() {
        val query = LocalContentSearchQuery("", SearchKind.ADVANCED, AdvancedSearchParams(tags = "-Horror"))
        assertEquals(listOf(1L), query.search(listOf(content(1), content(2, tags = setOf("Horror"))), 20).map { it.id })
    }

    @Test
    fun `main query and all advanced fields must match the same content`() {
        val query = LocalContentSearchQuery(
            "火影", SearchKind.ADVANCED, AdvancedSearchParams(title = "忍者", author = "岸本", tags = "冒险"),
        )
        val contents = listOf(
            content(1, "火影忍者", authors = setOf("岸本齐史"), tags = setOf("冒险")),
            content(2, "其他忍者", authors = setOf("岸本齐史"), tags = setOf("冒险")),
            content(3, "火影忍者", authors = setOf("其他作者"), tags = setOf("冒险")),
        )
        assertEquals(listOf(1L), query.search(contents, 20).map { it.id })
    }

    @Test
    fun `all alternative titles are searchable without case sensitivity`() {
        val query = LocalContentSearchQuery(" NARUTO ", SearchKind.TITLE)
        val contents = listOf(content(1, "火影忍者", altTitles = setOf("Naruto")))
        assertEquals(listOf(1L), query.search(contents, 20).map { it.id })
    }

    @Test
    fun `blank and incomplete negative tags never search the entire library`() {
        val query = LocalContentSearchQuery(" ", SearchKind.ADVANCED, AdvancedSearchParams(tags = " , - , "))
        assertFalse(query.hasCriteria)
        assertEquals(emptyList<Content>(), query.search(listOf(content(1)), 20))
    }

    @Test
    fun `normal searches preserve ranking and enforce the requested limit`() {
        val query = LocalContentSearchQuery("Naruto", SearchKind.SIMPLE)
        val contents = listOf(content(2, "Naruto Shippuden"), content(1, "Naruto"))
        assertEquals(listOf(1L), query.search(contents, 1).map { it.id })
        assertEquals(emptyList<Content>(), query.search(contents, 0))
    }

    private fun content(
        id: Long,
        title: String = "Work $id",
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
