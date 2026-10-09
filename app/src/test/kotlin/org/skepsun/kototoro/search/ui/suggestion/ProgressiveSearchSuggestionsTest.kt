package org.skepsun.kototoro.search.ui.suggestion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressiveSearchSuggestionsTest {

    @Test
    fun `local suggestions appear while the tracking request is still pending`() = runTest {
        val remote = CompletableDeferred<List<SearchSuggestionItem>>()
        val emissions = mutableListOf<SearchSuggestionState>()
        val job = launch {
            progressiveSearchSuggestions(local = { items("local") }, remote = { remote.await() })
                .collect { emissions += it }
        }
        runCurrent()
        assertEquals(listOf(SearchSuggestionState(items = items("local"), isRemoteLoading = true)), emissions)
        remote.complete(items("tracking"))
        job.join()
        assertEquals(items("local", "tracking"), emissions.last().items)
        assertTrue(!emissions.last().isRemoteLoading)
    }

    @Test
    fun `a new query cancels the old tracking request and never mixes suggestions`() = runTest {
        val queries = MutableSharedFlow<String>()
        val oldCancelled = CompletableDeferred<Unit>()
        val emissions = mutableListOf<SearchSuggestionState>()
        val job = backgroundScope.launch {
            queries.flatMapLatest { query ->
                progressiveSearchSuggestions(
                    local = { items("local:$query") },
                    remote = {
                        if (query == "old") {
                            try {
                                awaitCancellation()
                            } finally {
                                oldCancelled.complete(Unit)
                            }
                        } else {
                            items("remote:$query")
                        }
                    },
                )
            }.collect { emissions += it }
        }
        runCurrent()
        queries.emit("old")
        runCurrent()
        assertEquals(listOf(items("local:old")), emissions.map { it.items })
        queries.emit("new")
        runCurrent()
        assertTrue(oldCancelled.isCompleted)
        assertEquals(
            listOf(items("local:old"), items("local:new"), items("local:new", "remote:new")),
            emissions.map { it.items },
        )
        job.cancel()
    }

    @Test
    fun `no remote results leaves local suggestions unchanged`() = runTest {
        val emissions = mutableListOf<SearchSuggestionState>()
        progressiveSearchSuggestions(local = { items("local") }, remote = { emptyList() })
            .collect { emissions += it }
        assertEquals(items("local"), emissions.last().items)
        assertTrue(!emissions.last().isRemoteLoading)
    }

    @Test
    fun `closing the search cancels its pending tracking request`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val job = launch {
            progressiveSearchSuggestions(
                local = { items("local") },
                remote = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
            ).collect { }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `remote failure stops loading and preserves local suggestions`() = runTest {
        val error = IOException("offline")
        val emissions = mutableListOf<SearchSuggestionState>()
        progressiveSearchSuggestions(local = { items("local") }, remote = { throw error })
            .collect { emissions += it }

        assertEquals(items("local"), emissions.last().items)
        assertEquals(error, emissions.last().remoteError)
        assertTrue(!emissions.last().isRemoteLoading)
    }

    private fun items(vararg queries: String): List<SearchSuggestionItem> =
        queries.map { SearchSuggestionItem.RecentQuery(it) }
}
