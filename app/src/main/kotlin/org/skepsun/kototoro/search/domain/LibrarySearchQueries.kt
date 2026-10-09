package org.skepsun.kototoro.search.domain

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.domain.LibraryTextQuery
import org.skepsun.kototoro.parsers.model.Content
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A library page the search overlay can narrow to instead of searching every source.
 *
 * @param previewsMatches whether the overlay can list the page's matching works while typing; pages
 * whose rows are not plain works (update groups, feed logs, notes) only offer the filter action.
 */
enum class LibrarySearchScope(@StringRes val titleResId: Int, val previewsMatches: Boolean = true) {
    HISTORY(R.string.history),
    FAVOURITES(R.string.favourites),
    LOCAL(R.string.local_storage),
    SUGGESTIONS(R.string.suggestions),
    UPDATES(R.string.updated, previewsMatches = false),
    FEED(R.string.feed, previewsMatches = false),
    BOOKMARKS(R.string.bookmarks, previewsMatches = false),
}

/**
 * The text currently filtering each library page.
 *
 * Shared rather than passed down because the search overlay lives in the main shell while the
 * filtered lists live in space-bound view models. Kept per scope so a history filter never leaks
 * into favourites when both view models are alive.
 */
@Singleton
class LibrarySearchQueries @Inject constructor() {

    private val queries = EnumMap<LibrarySearchScope, MutableStateFlow<String>>(LibrarySearchScope::class.java).apply {
        LibrarySearchScope.entries.forEach { put(it, MutableStateFlow("")) }
    }
    private val preferScoped = EnumMap<LibrarySearchScope, Boolean>(LibrarySearchScope::class.java)

    fun query(scope: LibrarySearchScope): StateFlow<String> = queries.getValue(scope).asStateFlow()

    fun set(scope: LibrarySearchScope, value: String) {
        queries.getValue(scope).value = value.trim()
    }

    fun clear(scope: LibrarySearchScope) = set(scope, "")

    /** Whether the overlay should open on the page tab, remembered for the session per page. */
    fun prefersScopedTab(scope: LibrarySearchScope): Boolean =
        query(scope).value.isNotEmpty() || preferScoped[scope] == true

    fun setPrefersScopedTab(scope: LibrarySearchScope, value: Boolean) {
        preferScoped[scope] = value
    }
}

/**
 * Library contents matching [query] the same way the page filter does ([LibraryTextQuery] over
 * titles, authors and tags), keeping the library's own order. Blank queries match nothing here:
 * a suggestion list of the whole library is not a suggestion.
 */
internal fun Iterable<Content>.matchLibraryText(query: String, limit: Int): List<Content> {
    val textQuery = LibraryTextQuery(query)
    if (limit <= 0 || textQuery.isEmpty) return emptyList()
    return asSequence()
        .filter { content ->
            textQuery.matches(
                sequenceOf(content.title) +
                    content.altTitles.asSequence() +
                    content.authors.asSequence() +
                    content.tags.asSequence().map { it.title },
            )
        }
        .take(limit)
        .toList()
}
