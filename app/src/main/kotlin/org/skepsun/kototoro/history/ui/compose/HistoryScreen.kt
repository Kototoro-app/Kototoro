package org.skepsun.kototoro.history.ui.compose

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.compose.KototoroContentListScreen
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.compose.rememberRetainedPagingSnapshotState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.domain.ListFilterOption
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.paging.compose.LazyPagingItems

private const val MainRouteFlickerLogTag = "MainRouteFlicker"

private fun List<ListModel>.contentAtVisibleIndex(index: Int): String {
    val content = filterIsInstance<ContentListModel>().getOrNull(index) ?: return "none"
    return "${content.source.name}:${content.id}:${content.title}"
}

@Composable
fun HistoryScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    items: List<ListModel>,
    pagingItems: LazyPagingItems<ListModel>? = null,
    headerQuickFilter: QuickFilter? = null,
    listMode: ListMode,
    isRefreshing: Boolean,
    pullRefreshEnabled: Boolean = true,
    isStatsEnabled: Boolean,
    gridScale: Float,
    selectedItemsIds: Set<Long>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPrepareItemTransition: (ContentListModel, Rect?) -> Unit,
    onItemClick: (ContentListModel) -> Unit,
    onItemLongClick: (ContentListModel) -> Unit,
    onClearSelection: () -> Unit,
    onSelectionAction: (SelectionAction) -> Unit,
    onStatsClick: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    showQuickFilterInline: Boolean = true,
    showInlineSelectionTopBar: Boolean = true,
    viewModel: ContentListViewModel? = null,
    statsSummary: org.skepsun.kototoro.stats.domain.StatsDashboard? = null,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val quickFilter = remember(items, headerQuickFilter) {
        headerQuickFilter ?: (items.firstOrNull { it is QuickFilter } as? QuickFilter)
    }
    val contentItems = remember(items) {
        items.filterNot { it is QuickFilter }
    }
    // Retained paging snapshot: keep the visible window and realign by anchor item
    // id across a details-page round trip (details refresh invalidates the Room
    // paging source while the list is off-screen; a plain index restore then
    // points at the wrong generation).
    val retainedState = viewModel?.let { vm ->
        rememberRetainedPagingSnapshotState(
            host = vm,
            retainEnabled = true,
            leadingItems = contentItems,
            lazyPagingItems = pagingItems,
            listMode = listMode,
        )
    }
    val listState = retainedState?.listState ?: rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val detailedListState = retainedState?.detailedListState ?: rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val gridState = retainedState?.gridState ?: rememberSaveable(saver = LazyGridState.Saver) {
        LazyGridState()
    }
    LaunchedEffect(
        items.size,
        contentItems.size,
        quickFilter?.items?.size,
        listMode,
        isRefreshing,
        selectedItemsIds.size,
        contentPadding,
    ) {
        Log.d(
            MainRouteFlickerLogTag,
            "history screen state items=${items.size} contentItems=${contentItems.size} " +
                "quickItems=${quickFilter?.items?.size ?: -1} listMode=$listMode refreshing=$isRefreshing " +
                "selected=${selectedItemsIds.size} " +
                "paddingTop=${contentPadding.calculateTopPadding()} paddingBottom=${contentPadding.calculateBottomPadding()} " +
                "visibleGrid=${contentItems.contentAtVisibleIndex(gridState.firstVisibleItemIndex)} " +
                "visibleList=${contentItems.contentAtVisibleIndex(listState.firstVisibleItemIndex)} " +
                "visibleDetail=${contentItems.contentAtVisibleIndex(detailedListState.firstVisibleItemIndex)}",
        )
    }

    LaunchedEffect(listState, detailedListState, gridState) {
        snapshotFlow {
            "list=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} " +
                "detail=${detailedListState.firstVisibleItemIndex}/${detailedListState.firstVisibleItemScrollOffset} " +
                "grid=${gridState.firstVisibleItemIndex}/${gridState.firstVisibleItemScrollOffset}"
        }
            .distinctUntilChanged()
            .collect { scrollState ->
                Log.d(MainRouteFlickerLogTag, "history scroll $scrollState")
            }
    }
    KototoroContentListScreen(
        modifier = modifier,
        contentPadding = contentPadding,
        items = retainedState?.displayedItems ?: contentItems,
        pagingItems = retainedState?.displayedPagingItems ?: pagingItems,
        listMode = listMode,
        isRefreshing = isRefreshing ||
            (retainedState?.pagingIsRefreshing == true && retainedState.useRetainedPagingSnapshot == false),
        pullRefreshEnabled = pullRefreshEnabled,
        showRemoveOption = true,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        gridScale = gridScale,
        selectedItemsIds = selectedItemsIds,
        onPrepareItemTransition = onPrepareItemTransition,
        onItemClick = { item ->
            if (selectedItemsIds.isNotEmpty()) {
                hapticFeedback.performSelectionHapticFeedback()
            }
            retainedState?.let { state ->
                val (firstVisibleIndex, firstVisibleScrollOffset) = when (listMode) {
                    ListMode.GRID, ListMode.COMPACT_GRID ->
                        state.gridState.firstVisibleItemIndex to state.gridState.firstVisibleItemScrollOffset
                    ListMode.LIST ->
                        state.listState.firstVisibleItemIndex to state.listState.firstVisibleItemScrollOffset
                    ListMode.DETAILED_LIST ->
                        state.detailedListState.firstVisibleItemIndex to state.detailedListState.firstVisibleItemScrollOffset
                }
                val snapshotItems = state.currentRetainedSnapshot
                    ?.takeIf { state.useRetainedPagingSnapshot }
                    ?.items
                    ?: pagingItems?.itemSnapshotList?.items.orEmpty()
                // History renders a section/list header row at layout index 0
                // ahead of any leading `items` and the paging rows.
                val pagingAnchorIndex = (firstVisibleIndex - contentItems.size - 1).coerceAtLeast(0)
                state.captureOnNavigate(
                    item,
                    snapshotItems,
                    firstVisibleIndex,
                    firstVisibleScrollOffset,
                    listMode,
                    pagingAnchorIndex,
                )
            }
            onItemClick(item)
        },
        onItemLongClick = onItemLongClick,
        onClearSelection = onClearSelection,
        onSelectionAction = onSelectionAction,
        showInlineSelectionTopBar = showInlineSelectionTopBar,
        gridState = gridState,
        listState = listState,
        detailedListState = detailedListState,
        listHeader = {
            HistoryHeader(
                quickFilter = quickFilter.takeIf { showQuickFilterInline },
                isStatsEnabled = isStatsEnabled,
                onStatsClick = onStatsClick,
                onQuickFilterOptionClick = onQuickFilterOptionClick,
                statsSummary = statsSummary,
            )
        },
    )
}

@Composable
private fun HistoryHeader(
    quickFilter: QuickFilter?,
    isStatsEnabled: Boolean,
    onStatsClick: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    statsSummary: org.skepsun.kototoro.stats.domain.StatsDashboard?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        if (isStatsEnabled && statsSummary != null && statsSummary.hasAnyActivity()) {
            HistoryStatsSummaryCard(
                dashboard = statsSummary,
                onClick = onStatsClick,
                modifier = Modifier.padding(horizontal = AppLayoutTokens.screenHorizontalPadding),
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (quickFilter != null) {
            org.skepsun.kototoro.list.ui.compose.QuickFilterSection(
                quickFilter = quickFilter.withMacroOptionsFirst(),
                onQuickFilterOptionClick = onQuickFilterOptionClick,
            )
        }
    }
}

private fun org.skepsun.kototoro.stats.domain.StatsDashboard.hasAnyActivity(): Boolean {
    return totalDuration > 0L || sessionCount > 0 || workCount > 0 || activeDays > 0
}

private fun QuickFilter.withMacroOptionsFirst(): QuickFilter {
    return copy(
        items = items.sortedBy { chip ->
            when (chip.data as? ListFilterOption) {
                ListFilterOption.Downloaded,
                is ListFilterOption.Macro,
                is ListFilterOption.Inverted -> 0
                is ListFilterOption.Tag -> 1
                is ListFilterOption.Source -> 2
                else -> 3
            }
        },
    )
}
