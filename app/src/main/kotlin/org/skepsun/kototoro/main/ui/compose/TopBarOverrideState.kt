package org.skepsun.kototoro.main.ui.compose

import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentSource

interface TopBarOverrideState

data class CompactTopBarTabItem(
    val id: Long,
    val title: String,
)

data class CompactTabsTopBarOverrideState(
    val items: List<CompactTopBarTabItem>,
    val selectedItemId: Long,
    val onItemSelected: (Long) -> Unit,
) : TopBarOverrideState

data class CompactFilterRailItem(
    val id: String,
    val title: String,
    val isSelected: Boolean,
    val source: ContentSource? = null,
    val onClick: () -> Unit,
)

data class CompactFilterRailOverrideState(
    val items: List<CompactFilterRailItem>,
) : TopBarOverrideState

fun List<CompactFilterRailItem>.selectedFirst(): List<CompactFilterRailItem> {
    if (size < 2) return this
    val selectedItems = ArrayList<CompactFilterRailItem>(size)
    val unselectedItems = ArrayList<CompactFilterRailItem>(size)
    for (item in this) {
        if (item.isSelected) {
            selectedItems += item
        } else {
            unselectedItems += item
        }
    }
    if (selectedItems.isEmpty() || unselectedItems.isEmpty()) return this
    return buildList(size) {
        addAll(selectedItems)
        addAll(unselectedItems)
    }
}

data class LayeredTopBarOverrideState(
    val tabsState: CompactTabsTopBarOverrideState? = null,
    val filterRailState: CompactFilterRailOverrideState? = null,
    val contextualOverrideState: TopBarOverrideState? = null,
    val keepTabsExpandedWhenCollapsed: Boolean = false,
    val sortOrders: List<ListSortOrder> = emptyList(),
    val selectedSortOrder: ListSortOrder? = null,
    val onSortOrderSelected: (ListSortOrder) -> Unit = {},
) : TopBarOverrideState

data class RouteScopedTopBarOverrideState(
    val ownerRoute: String,
    val state: TopBarOverrideState?,
) : TopBarOverrideState

/**
 * Structural equivalence for the top-bar override protocol, deliberately ignoring
 * function-typed fields.
 *
 * Routes re-report their override state on every recomposition (an unkeyed SideEffect
 * for history, a re-remembered DisposableEffect for favourites) and each report carries
 * freshly built callbacks. Data-class `==` therefore reports "changed" forever, even
 * while nothing semantic moved. Combined with the shell storing the report in a
 * snapshot map that KototoroApp itself reads, that turned "report again" into a
 * self-sustaining invalidate -> recompose -> report loop: an untouched history or
 * favourites screen kept recomposing at ~60 Hz and re-measuring its whole list
 * (~620 frames rendered per idle 5 s on a 120 Hz panel, feed unaffected).
 *
 * Callback identity is safe to ignore here: the stored callback keeps referencing the
 * state objects / view models it captured, and any semantic field change (selection,
 * tabs, sort order) goes through this gate and stores a fresh instance with fresh
 * callbacks, so the wiring never outlives the change it belongs to.
 */
internal fun overrideStateEquivalent(a: TopBarOverrideState?, b: TopBarOverrideState?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return when (a) {
        is LayeredTopBarOverrideState -> b is LayeredTopBarOverrideState &&
            overrideStateEquivalent(a.tabsState, b.tabsState) &&
            overrideStateEquivalent(a.filterRailState, b.filterRailState) &&
            overrideStateEquivalent(a.contextualOverrideState, b.contextualOverrideState) &&
            a.keepTabsExpandedWhenCollapsed == b.keepTabsExpandedWhenCollapsed &&
            a.sortOrders == b.sortOrders &&
            a.selectedSortOrder == b.selectedSortOrder

        is RouteScopedTopBarOverrideState -> b is RouteScopedTopBarOverrideState &&
            a.ownerRoute == b.ownerRoute &&
            overrideStateEquivalent(a.state, b.state)

        is CompactTabsTopBarOverrideState -> b is CompactTabsTopBarOverrideState &&
            a.items == b.items &&
            a.selectedItemId == b.selectedItemId

        is CompactFilterRailOverrideState -> b is CompactFilterRailOverrideState &&
            filterRailItemsEquivalent(a.items, b.items)

        is ContentSelectionTopBarOverrideState -> b is ContentSelectionTopBarOverrideState &&
            a.selectedCount == b.selectedCount &&
            a.isAllNonLocal == b.isAllNonLocal &&
            a.isSingleSelection == b.isSingleSelection &&
            a.showRemoveOption == b.showRemoveOption &&
            a.supportedActions == b.supportedActions &&
            a.allPinned == b.allPinned &&
            a.preferredInlineActions == b.preferredInlineActions &&
            a.removeActionIconRes == b.removeActionIconRes &&
            a.removeActionTitleRes == b.removeActionTitleRes &&
            a.fixActionTitleRes == b.fixActionTitleRes &&
            a.includeContextualActions == b.includeContextualActions

        is org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState -> b is
            org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState &&
            a.selectedCount == b.selectedCount &&
            a.isSingleSelection == b.isSingleSelection &&
            a.canPin == b.canPin &&
            a.canUnpin == b.canUnpin &&
            a.canDisable == b.canDisable &&
            a.canDelete == b.canDelete &&
            a.markEmptyTitleRes == b.markEmptyTitleRes

        // Types without callback fields keep the plain structural comparison.
        else -> a == b
    }
}

private fun filterRailItemsEquivalent(a: List<CompactFilterRailItem>, b: List<CompactFilterRailItem>): Boolean {
    if (a.size != b.size) return false
    for (index in a.indices) {
        val x = a[index]
        val y = b[index]
        if (x.id != y.id || x.title != y.title || x.isSelected != y.isSelected || x.source != y.source) {
            return false
        }
    }
    return true
}

/**
 * Same rule as [overrideStateEquivalent] for contextual menu action lists: the visible
 * action set (title + icon) decides equivalence, not the freshly built
 * [KototoroTopBarMenuAction.onClick] callbacks.
 */
internal fun menuActionsEquivalent(a: List<KototoroTopBarMenuAction>, b: List<KototoroTopBarMenuAction>): Boolean {
    if (a.size != b.size) return false
    for (index in a.indices) {
        val x = a[index]
        val y = b[index]
        if (x.titleRes != y.titleRes || x.iconRes != y.iconRes) return false
    }
    return true
}

data class RouteScopedTopBarMenuActions(
    val ownerRoute: String,
    val actions: List<KototoroTopBarMenuAction>,
)

data class ContentSelectionTopBarOverrideState(
    val selectedCount: Int,
    val isAllNonLocal: Boolean,
    val isSingleSelection: Boolean,
    val showRemoveOption: Boolean = false,
    val supportedActions: Set<SelectionAction>,
    val allPinned: Boolean = false,
    val preferredInlineActions: List<SelectionAction>? = null,
    val removeActionIconRes: Int? = null,
    val removeActionTitleRes: Int? = null,
    val fixActionTitleRes: Int? = null,
    val includeContextualActions: Boolean = true,
    val onClearSelection: () -> Unit,
    val onActionClick: (SelectionAction) -> Unit,
) : TopBarOverrideState
