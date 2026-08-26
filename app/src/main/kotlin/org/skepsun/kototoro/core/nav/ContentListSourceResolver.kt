package org.skepsun.kototoro.core.nav

import androidx.lifecycle.SavedStateHandle

/**
 * Single source of truth for resolving the source name of a source/content-list
 * destination.
 *
 * Navigation 3 entries do not map route arguments into their SavedStateHandle, so when a
 * list is opened in-app (via [PendingContentListNavigation]) the caller only receives the
 * source name through the process-wide hand-off. Every consumer along the creation path —
 * [org.skepsun.kototoro.remotelist.ui.ContentListSourceGateViewModel],
 * [org.skepsun.kototoro.remotelist.ui.RemoteListViewModel] and
 * [org.skepsun.kototoro.filter.ui.FilterCoordinator] — MUST resolve through this helper so
 * the SavedStateHandle → pending-navigation fallback chain cannot diverge again.
 *
 * [consumePending] must be `true` for exactly ONE consumer in the creation path (the last
 * one, [org.skepsun.kototoro.remotelist.ui.RemoteListViewModel]): the earlier consumers
 * (the gate and the filter coordinator) peek the hand-off without clearing it, so the
 * process-wide value is consumed exactly once.
 */
fun resolveContentListSourceName(
    savedStateHandle: SavedStateHandle,
    consumePending: Boolean,
): String? {
    return savedStateHandle.get<String>(AppRouter.KEY_SOURCE)
        ?: savedStateHandle.get<String>("sourceName")
        ?: if (consumePending) {
            PendingContentListNavigation.consumeSourceName()
        } else {
            PendingContentListNavigation.peekSourceName()
        }
}
