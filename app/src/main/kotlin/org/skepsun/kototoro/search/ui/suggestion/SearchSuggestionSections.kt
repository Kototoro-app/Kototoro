package org.skepsun.kototoro.search.ui.suggestion

import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.tracking.discovery.domain.EntityType

data class SearchSuggestionSection(
    val key: String,
    @StringRes val titleResId: Int,
    val items: List<SearchSuggestionItem>,
    val service: ScrobblerService? = null,
    /** Formats [titleResId] when set, e.g. the page name in "Matches in History". */
    @StringRes val titleArgResId: Int? = null,
)

fun List<SearchSuggestionItem>.toSuggestionSections(): List<SearchSuggestionSection> = buildList {
    fun section(key: String, @StringRes title: Int, matches: (SearchSuggestionItem) -> Boolean) {
        val items = this@toSuggestionSections.filter(matches)
        if (items.isNotEmpty()) add(SearchSuggestionSection(key, title, items))
    }
    this@toSuggestionSections.filterIsInstance<SearchSuggestionItem.LibraryMatch>().takeIf { it.isNotEmpty() }
        ?.let { matches ->
            add(
                SearchSuggestionSection(
                    key = "library",
                    titleResId = R.string.library_search_matches,
                    items = matches,
                    titleArgResId = matches.first().scope.titleResId,
                ),
            )
        }
    section("local", R.string.search_local_suggestions) {
        it is SearchSuggestionItem.LocalEntityList || it is SearchSuggestionItem.ContentList
    }
    section("queries", R.string.recent_queries) { it is SearchSuggestionItem.RecentQuery }
    section("hints", R.string.suggested_queries) { it is SearchSuggestionItem.Hint }
    section("authors", R.string.authors) { it is SearchSuggestionItem.Author }
    section("tags", R.string.tags) { it is SearchSuggestionItem.Tags }
    section("sources", R.string.remote_sources) { it is SearchSuggestionItem.Source }
    section("recent_sources", R.string.recent_sources) { it is SearchSuggestionItem.SourceTip }
    this@toSuggestionSections.filterIsInstance<SearchSuggestionItem.TrackingEntityList>().forEach { tracking ->
        EntityType.entries.forEach { type ->
            val entities = tracking.items.filter { it.entityType == type }.distinctBy { it.remoteId }
            if (entities.isNotEmpty()) {
                val title = when (type) {
                    EntityType.WORK -> R.string.entity_graph_type_work
                    EntityType.PERSON -> R.string.entity_graph_type_person
                    EntityType.CHARACTER -> R.string.entity_graph_type_character
                    EntityType.ORGANIZATION -> R.string.entity_graph_type_organization
                }
                add(
                    SearchSuggestionSection(
                        key = "tracking_${tracking.service.name}_${type.name}",
                        titleResId = title,
                        items = listOf(tracking.copy(items = entities)),
                        service = tracking.service,
                    ),
                )
            }
        }
    }
    section("messages", R.string.search_suggestions) { it is SearchSuggestionItem.Text }
}
