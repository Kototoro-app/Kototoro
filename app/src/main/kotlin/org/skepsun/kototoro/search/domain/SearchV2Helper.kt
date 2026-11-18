package org.skepsun.kototoro.search.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

class SearchV2Helper @AssistedInject constructor(
    @Assisted private val source: ContentSource,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val dataRepository: ContentDataRepository,
    private val settings: AppSettings,
) {

    suspend operator fun invoke(query: String, kind: SearchKind, advanced: AdvancedSearchParams? = null): SearchResults? {
        if (settings.isNsfwContentDisabled && source.isNsfw()) {
            return null
        }
        val repository = mangaRepositoryFactory.create(source)
        val listFilter = repository.getFilter(query, kind, advanced) ?: return null
        val sortOrder = repository.getSortOrder(kind)
        val list = repository.getList(0, sortOrder, listFilter)
        if (list.isEmpty()) {
            return null
        }
        val result = list.toMutableList()
        result.postFilter(query, kind, advanced)
        return SearchResults(listFilter = listFilter, sortOrder = sortOrder, manga = result.sortedByRelevance(query, kind, advanced))
    }

    private suspend fun ContentRepository.getFilter(query: String, kind: SearchKind, advanced: AdvancedSearchParams? = null): ContentListFilter? {
        // Only tag searches need the source's tags; the rules ignore them for the other kinds.
        val tags = if (kind == SearchKind.TAG || kind == SearchKind.ADVANCED) {
            this@SearchV2Helper.dataRepository.findTags(this.source) + runCatchingCancellable {
                this@getFilter.getFilterOptions().availableTags
            }.onFailure { e ->
                e.printStackTraceDebug()
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val plan = SearchRules.plan(
            kind = kind,
            query = query,
            advanced = advanced,
            supportsSearch = filterCapabilities.isSearchSupported,
            supportsAuthorSearch = filterCapabilities.isAuthorSearchSupported,
            knownTags = tags.map { it.title },
        ) ?: return null
        fun resolve(titles: Set<String>) = titles.mapNotNullTo(linkedSetOf()) { title ->
            tags.find { it.title.equals(title, ignoreCase = true) }
        }
        return ContentListFilter(
            query = plan.query,
            tags = resolve(plan.tags),
            tagsExclude = resolve(plan.tagsExclude),
            author = plan.author,
        )
    }

    private fun MutableList<Content>.postFilter(query: String, kind: SearchKind, advanced: AdvancedSearchParams? = null) {
        if (settings.isNsfwContentDisabled) {
            removeAll { it.isNsfw() }
        }
        val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
        if (!globalTagBlacklist.isEmpty) {
            removeAll { it in globalTagBlacklist }
        }
        retainAll { SearchRules.accepts(it.toCandidate(), kind, query, advanced) }
    }

    private fun List<Content>.sortedByRelevance(query: String, kind: SearchKind, advanced: AdvancedSearchParams? = null): List<Content> =
        SearchRules.sortByRelevance(this, kind, query, advanced) { it.toCandidate() }

    private fun Content.toCandidate() = SearchCandidate(
        title = title,
        altTitle = altTitle,
        authors = authors,
        tags = tags.map { it.title },
    )

    private fun ContentRepository.getSortOrder(kind: SearchKind): SortOrder {
        val preferred = if (SearchRules.prefersPopularity(kind)) SortOrder.POPULARITY else SortOrder.RELEVANCE
        return if (preferred in sortOrders) {
            preferred
        } else {
            defaultSortOrder
        }
    }


    @AssistedFactory
    interface Factory {

        fun create(source: ContentSource): SearchV2Helper
    }
}
