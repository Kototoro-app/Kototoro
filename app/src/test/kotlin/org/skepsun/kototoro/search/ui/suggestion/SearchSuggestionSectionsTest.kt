package org.skepsun.kototoro.search.ui.suggestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.search.ui.suggestion.model.TrackingEntity
import org.skepsun.kototoro.tracking.discovery.domain.EntityType

class SearchSuggestionSectionsTest {

    @Test
    fun `remote works people and characters stay in separate sections even with the same id`() {
        val entities = listOf(EntityType.WORK, EntityType.PERSON, EntityType.CHARACTER).map { type ->
            TrackingEntity(ScrobblerService.BANGUMI, type, 1L, type.name)
        }

        val sections = listOf(SearchSuggestionItem.TrackingEntityList(ScrobblerService.BANGUMI, entities))
            .toSuggestionSections()

        assertEquals(3, sections.size)
        assertEquals(3, sections.map { it.key }.toSet().size)
        val displayed = sections.flatMap { it.items }.filterIsInstance<SearchSuggestionItem.TrackingEntityList>()
        assertEquals(entities.toSet(), displayed.flatMap { it.items }.toSet())
        assertEquals(listOf(1, 1, 1), displayed.map { it.items.size })
    }

    @Test
    fun `appending remote suggestions preserves the local section keys and order`() {
        val local = listOf(SearchSuggestionItem.RecentQuery("query"), SearchSuggestionItem.Author("author"))
        val remote = SearchSuggestionItem.TrackingEntityList(
            ScrobblerService.MAL,
            listOf(TrackingEntity(ScrobblerService.MAL, EntityType.WORK, 1L, "work")),
        )

        val before = local.toSuggestionSections()
        val after = (local + remote).toSuggestionSections()

        assertEquals(before, after.take(before.size))
    }
}
