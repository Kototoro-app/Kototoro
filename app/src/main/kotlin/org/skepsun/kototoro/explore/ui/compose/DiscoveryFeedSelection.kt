package org.skepsun.kototoro.explore.ui.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.explore.ui.DiscoveryFeedBulkAddPlan
import org.skepsun.kototoro.explore.ui.DiscoveryFeedBulkAddResult
import org.skepsun.kototoro.explore.ui.DiscoveryFeedUiState
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.compose.ContentSelectionTopBarOverrideState

// Discover's multi-select reuses the library's selection bar: count, close, "select all" (the row the
// selection last changed in) and the favourite action, which opens the shared category dialog.

internal val DiscoveryFeedSelectionActions: List<SelectionAction> =
    listOf(SelectionAction.SELECT_ALL, SelectionAction.FAVOURITE)

internal fun discoveryFeedSelectionTopBarState(
    uiState: DiscoveryFeedUiState,
    onClearSelection: () -> Unit,
    onActionClick: (SelectionAction) -> Unit,
): ContentSelectionTopBarOverrideState? {
    if (!uiState.isSelectionMode) return null
    return ContentSelectionTopBarOverrideState(
        selectedCount = uiState.selectedIds.size,
        isAllNonLocal = true,
        isSingleSelection = uiState.selectedIds.size == 1,
        supportedActions = DiscoveryFeedSelectionActions.toSet(),
        preferredInlineActions = DiscoveryFeedSelectionActions,
        includeContextualActions = false,
        onClearSelection = onClearSelection,
        onActionClick = onActionClick,
    )
}

/** A string resource and its arguments. */
internal data class DiscoveryFeedMessage(val resId: Int, val args: List<Int>)

/** What to tell before the dialog opens: only when every selected work is already in the library. */
internal fun discoveryFeedBulkAddPlanMessage(plan: DiscoveryFeedBulkAddPlan): DiscoveryFeedMessage? =
    if (plan.toAdd.isEmpty() && plan.alreadyInLibrary > 0) {
        DiscoveryFeedMessage(R.string.discovery_feed_bulk_all_in_library, listOf(plan.alreadyInLibrary))
    } else {
        null
    }

/** What to tell once the dialog closes; nothing when no category was picked. */
internal fun discoveryFeedBulkAddResultMessage(result: DiscoveryFeedBulkAddResult): DiscoveryFeedMessage? = when {
    result.added <= 0 -> null
    result.skipped > 0 -> DiscoveryFeedMessage(
        R.string.discovery_feed_bulk_added_skipped,
        listOf(result.added, result.skipped),
    )
    else -> DiscoveryFeedMessage(R.string.discovery_feed_bulk_added, listOf(result.added))
}
