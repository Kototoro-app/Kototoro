package org.skepsun.kototoro.history.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The history page tells two different stories for an empty list: a library that never held
 * history ("no history yet") and a library whose history is hidden by the active filter, tab,
 * source tags or search ("nothing matches your filters" + reset). Before this rule both cases
 * produced the filtered copy, so a brand-new install — where the offline Downloaded default is
 * applied automatically — opened history and was told its filters hid a history it never had.
 */
class HistoryEmptyStateRulesTest {

    @Test
    fun `brand new install shows the plain holder, not a filter complaint`() {
        assertEquals(
            HistoryEmptyStateKind.NO_HISTORY,
            historyEmptyStateKind(hasAnyHistory = false, isNarrowed = true),
        )
    }

    @Test
    fun `empty history with nothing narrowing shows the plain holder`() {
        assertEquals(
            HistoryEmptyStateKind.NO_HISTORY,
            historyEmptyStateKind(hasAnyHistory = false, isNarrowed = false),
        )
    }

    @Test
    fun `history hidden by a filter is reported as filtered`() {
        assertEquals(
            HistoryEmptyStateKind.HIDDEN_BY_FILTERS,
            historyEmptyStateKind(hasAnyHistory = true, isNarrowed = true),
        )
    }

    @Test
    fun `history that renders rows never asks for an empty state`() {
        // Defensive: with rows on screen the page must not fall back to "filters hid everything".
        assertEquals(
            HistoryEmptyStateKind.NO_HISTORY,
            historyEmptyStateKind(hasAnyHistory = true, isNarrowed = false),
        )
    }
}
