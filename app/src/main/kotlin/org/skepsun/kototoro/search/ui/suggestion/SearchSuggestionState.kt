package org.skepsun.kototoro.search.ui.suggestion

import org.skepsun.kototoro.search.domain.LibrarySearchScope
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem

data class SearchSuggestionState(
    val query: String = "",
    val items: List<SearchSuggestionItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRemoteLoading: Boolean = false,
    val remoteError: Throwable? = null,
    /** The library page these suggestions were narrowed to, `null` for the global search. */
    val libraryScope: LibrarySearchScope? = null,
)
