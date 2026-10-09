package org.skepsun.kototoro.search.ui.suggestion

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem

internal fun progressiveSearchSuggestions(
    local: suspend () -> List<SearchSuggestionItem>,
    remote: (suspend () -> List<SearchSuggestionItem>)?,
): Flow<SearchSuggestionState> = flow {
    coroutineScope {
        val remoteSuggestions = remote?.let { request -> async { runCatchingCancellable { request() } } }
        val localSuggestions = local()
        emit(SearchSuggestionState(items = localSuggestions, isRemoteLoading = remoteSuggestions != null))
        if (remoteSuggestions != null) {
            val result = remoteSuggestions.await()
            emit(
                SearchSuggestionState(
                    items = localSuggestions + result.getOrDefault(emptyList()),
                    remoteError = result.exceptionOrNull(),
                ),
            )
        }
    }
}
