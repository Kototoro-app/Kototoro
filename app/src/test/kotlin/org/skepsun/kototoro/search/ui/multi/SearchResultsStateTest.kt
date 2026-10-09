package org.skepsun.kototoro.search.ui.multi

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.IOException

class SearchResultsStateTest {

    @Test
    fun `hiding empty sources keeps failed sources visible`() {
        val failed = section("failed", error = IOException())
        val empty = section("empty")
        val state = SearchResultsState(sections = listOf(empty, failed), canSearchDisabledSources = false)
        assertEquals(listOf(failed), state.asListModels(hideEmpty = true))
        assertEquals(1, state.failedSources)
    }

    @Test
    fun `an empty search offers disabled sources unless the scope forbids it`() {
        val state = SearchResultsState()
        val empty = state.asListModels(hideEmpty = true).single() as EmptyState
        assertEquals(R.string.search_disabled_sources, empty.actionStringRes)
        val scoped = state.copy(canSearchDisabledSources = false).asListModels(hideEmpty = true).single() as EmptyState
        assertEquals(0, scoped.actionStringRes)
    }

    @Test
    fun `refresh retains old results until the first new section arrives`() {
        val old = section("old", hasContent = true)
        val fresh = section("new", hasContent = true)
        val state = SearchResultsState(sections = listOf(old), isSearching = true, isRefreshing = true)
        assertTrue(old in state.asListModels(hideEmpty = true))
        assertEquals(listOf(old), state.withResult(null).sections)
        assertEquals(listOf(fresh), state.withResult(fresh).sections)
    }

    @Test
    fun `refresh with no results removes the stale results when it finishes`() {
        val state = SearchResultsState(sections = listOf(section("old", hasContent = true)), isRefreshing = true)
        assertEquals(emptyList<SearchResultsListModel>(), state.finished().sections)
        assertFalse(state.finished().isSearching)
    }

    @Test
    fun `retry replaces only the failed section in its original position`() {
        val first = section("first", hasContent = true)
        val failed = section("failed", error = IOException())
        val last = section("last", hasContent = true)
        val successful = failed.copy(list = listOf(mockk()), error = null)
        val state = SearchResultsState(sections = listOf(first, failed, last))
        assertEquals(listOf(first, successful, last), state.withResult(successful, failed).sections)
        assertEquals(0, state.withResult(successful, failed).failedSources)
        assertEquals(listOf(first, last), state.withResult(null, failed).sections)
    }

    private fun section(name: String, hasContent: Boolean = false, error: Throwable? = null) = SearchResultsListModel(
        titleResId = 0,
        source = object : ContentSource {
            override val name = name
            override val locale = ""
            override val contentType = ContentType.MANGA
        },
        listFilter = null,
        sortOrder = null,
        list = if (hasContent) listOf(mockk()) else emptyList(),
        error = error,
    )
}
