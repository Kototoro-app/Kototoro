package org.skepsun.kototoro.search.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.contains
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.almostEquals
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

private const val MATCH_THRESHOLD_DEFAULT = 0.2f

class SearchV2Helper @AssistedInject constructor(
	@Assisted private val source: ContentSource,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val dataRepository: ContentDataRepository,
	private val settings: AppSettings,
) {

	suspend operator fun invoke(query: String, kind: SearchKind): SearchResults? {
		if (settings.isNsfwContentDisabled && source.isNsfw()) {
			return null
		}
		val repository = mangaRepositoryFactory.create(source)
		val listFilter = repository.getFilter(query, kind) ?: return null
		val sortOrder = repository.getSortOrder(kind)
		val list = repository.getList(0, sortOrder, listFilter)
		if (list.isEmpty()) {
			return null
		}
		val result = list.toMutableList()
		result.postFilter(query, kind)
		result.sortByRelevance(query, kind)
		return SearchResults(listFilter = listFilter, sortOrder = sortOrder, manga = result)
	}

	private suspend fun ContentRepository.getFilter(query: String, kind: SearchKind): ContentListFilter? = when (kind) {
		SearchKind.SIMPLE,
		SearchKind.TITLE -> if (filterCapabilities.isSearchSupported) {
			ContentListFilter(query = query)
		} else {
			null
		}

		SearchKind.AUTHOR -> if (filterCapabilities.isAuthorSearchSupported) {
			ContentListFilter(author = query)
		} else if (filterCapabilities.isSearchSupported) {
			ContentListFilter(query = query)
		} else {
			null
		}

		SearchKind.TAG -> {
			val tags = this@SearchV2Helper.dataRepository.findTags(this.source) + runCatchingCancellable {
				this@getFilter.getFilterOptions().availableTags
			}.onFailure { e ->
				e.printStackTraceDebug()
			}.getOrDefault(emptySet())
			
			val queryExcludeTagsStr = query.split(",").map { it.trim() }.filter { it.isNotEmpty() && it[0] == '-' }
			val matchedExcludeTags = queryExcludeTagsStr.mapNotNull { tagQ -> 
				val newTagQ = tagQ.substring(1)
				tags.find { x -> x.title.equals(newTagQ, ignoreCase = true) }
			}.toSet()
			val queryTagsStr = query.split(",").map { it.trim() }.filter { it.isNotEmpty() && it[0] != '-'}
			val matchedTags = queryTagsStr.mapNotNull { tagQ -> 
				tags.find { x -> x.title.equals(tagQ, ignoreCase = true) }
			}.toSet()
			
			if (matchedTags.isNotEmpty() || matchedExcludeTags.isNotEmpty()) {
				ContentListFilter(tags = matchedTags, tagsExclude = matchedExcludeTags)
			} else {
				null
			}
		}
	}

	private fun MutableList<Content>.postFilter(query: String, kind: SearchKind) {
		if (settings.isNsfwContentDisabled) {
			removeAll { it.isNsfw() }
		}
		when (kind) {
			SearchKind.TITLE -> retainAll { m ->
				m.matches(query, MATCH_THRESHOLD_DEFAULT)
			}

			SearchKind.AUTHOR -> retainAll { m ->
				m.authors.isEmpty() || m.authors.contains(query, ignoreCase = true)
			}

			SearchKind.SIMPLE, // no filtering expected
			SearchKind.TAG -> Unit
		}
	}

	private fun MutableList<Content>.sortByRelevance(query: String, kind: SearchKind) {
		when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> sortBy { m ->
				minOf(m.title.levenshteinDistance(query), m.altTitle?.levenshteinDistance(query) ?: Int.MAX_VALUE)
			}

			SearchKind.AUTHOR -> sortByDescending { m ->
				m.authors.contains(query, ignoreCase = true)
			}

			SearchKind.TAG -> sortByDescending { m ->
				val queryExcludeTagsStr = query.split(",").map { it.trim() }.filter { it.isNotEmpty() && it[0] == "-"}
				val queryTagsStr = query.split(",").map { it.trim() }.filter { it.isNotEmpty() && it[0] != '-'}
				m.tags.count { tag -> queryTagsStr.any { q -> tag.title.equals(q, ignoreCase = true) } } - m.tags.count { tag -> queryExcludeTagsStr.any { q -> tag.title.equals(q.substring(1), ignoreCase = true) } }
			}
		}
	}

	private fun ContentRepository.getSortOrder(kind: SearchKind): SortOrder {
		val preferred: SortOrder = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE,
			SearchKind.AUTHOR -> SortOrder.RELEVANCE

			SearchKind.TAG -> SortOrder.POPULARITY
		}
		return if (preferred in sortOrders) {
			preferred
		} else {
			defaultSortOrder
		}
	}


	private fun Content.matches(query: String, threshold: Float): Boolean {
		return matchesTitles(title, query, threshold) || matchesTitles(altTitle, query, threshold)
	}

	private fun matchesTitles(a: String?, b: String?, threshold: Float): Boolean {
		return !a.isNullOrEmpty() && !b.isNullOrEmpty() && a.almostEquals(b, threshold)
	}

	@AssistedFactory
	interface Factory {

		fun create(source: ContentSource): SearchV2Helper
	}
}
