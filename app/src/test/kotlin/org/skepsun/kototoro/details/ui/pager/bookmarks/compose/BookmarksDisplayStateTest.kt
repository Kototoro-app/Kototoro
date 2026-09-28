package org.skepsun.kototoro.details.ui.pager.bookmarks.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.LoadingState

class BookmarksDisplayStateTest {

    @Test
    fun `loading placeholder shows loading`() {
        assertEquals(BookmarksDisplayState.Loading, bookmarksDisplayState(listOf(LoadingState)))
    }

    @Test
    fun `an empty-state model or no items shows the empty state`() {
        val empty = EmptyState(0, R.string.no_bookmarks_yet, R.string.no_bookmarks_summary, 0)
        assertEquals(
            BookmarksDisplayState.Empty(R.string.no_bookmarks_yet, R.string.no_bookmarks_summary),
            bookmarksDisplayState(listOf(empty)),
        )
        assertEquals(
            BookmarksDisplayState.Empty(R.string.no_bookmarks_yet, R.string.no_bookmarks_summary),
            bookmarksDisplayState(emptyList()),
        )
    }

    @Test
    fun `headers without bookmarks still count as empty`() {
        assertEquals(
            BookmarksDisplayState.Empty(R.string.no_bookmarks_yet, R.string.no_bookmarks_summary),
            bookmarksDisplayState(listOf(ListHeader("Chapter 1"))),
        )
    }
}
