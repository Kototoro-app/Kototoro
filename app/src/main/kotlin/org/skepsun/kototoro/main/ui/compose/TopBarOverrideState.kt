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
    val onClearSelection: () -> Unit,
    val onActionClick: (SelectionAction) -> Unit,
) : TopBarOverrideState
