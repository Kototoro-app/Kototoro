package org.skepsun.kototoro.history.domain

/** Which holder the history page shows when the derived (filtered) list comes back empty. */
enum class HistoryEmptyStateKind {
    /** The library holds no history at all, so nothing was taken away by filters. */
    NO_HISTORY,

    /** There is history, but the active filters / tab / space / search leave nothing visible. */
    HIDDEN_BY_FILTERS,
}

/**
 * Picks the empty state for a history page whose filtered rows are empty.
 *
 * The favourites page has always split these two cases (`FavouritesListHost.emptyStateOf` reads
 * `library.allEntityIds` before filtering); history did not, so *any* active filter produced
 * "There are no manga matching the filters you selected" plus a useless "Reset filter" button.
 * The offline default below made that the first thing a brand-new install saw: the Downloaded
 * quick filter is applied automatically, and a history that never existed was then described as
 * hidden by filters.
 *
 * @param hasAnyHistory whether the unfiltered snapshot holds any row
 * @param isNarrowed whether a filter, group tab, space source tag or search query is narrowing the list
 */
fun historyEmptyStateKind(hasAnyHistory: Boolean, isNarrowed: Boolean): HistoryEmptyStateKind =
    if (hasAnyHistory && isNarrowed) HistoryEmptyStateKind.HIDDEN_BY_FILTERS else HistoryEmptyStateKind.NO_HISTORY
