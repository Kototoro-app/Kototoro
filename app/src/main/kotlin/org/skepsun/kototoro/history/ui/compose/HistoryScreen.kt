package org.skepsun.kototoro.history.ui.compose

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.list.ui.compose.KototoroContentListScreen
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.domain.ListFilterOption
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MainRouteFlickerLogTag = "MainRouteFlicker"

private fun List<ListModel>.contentAtVisibleIndex(index: Int): String {
    val content = filterIsInstance<ContentListModel>().getOrNull(index) ?: return "none"
    return "${content.source.name}:${content.id}:${content.title}"
}

@Composable
fun HistoryScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    items: List<ListModel>,
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
    onContinueReadingClick: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    showContinueReadingButton: Boolean,
    showQuickFilterInline: Boolean = true,
    showInlineSelectionTopBar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val quickFilter = remember(items, headerQuickFilter) {
        headerQuickFilter ?: (items.firstOrNull { it is QuickFilter } as? QuickFilter)
    }
    val contentItems = remember(items) {
        items.filterNot { it is QuickFilter }
    }
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val detailedListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) {
        LazyGridState()
    }
    LaunchedEffect(
        items.size,
        contentItems.size,
        quickFilter?.items?.size,
        listMode,
        isRefreshing,
        selectedItemsIds.size,
        showContinueReadingButton,
        contentPadding,
    ) {
        Log.d(
            MainRouteFlickerLogTag,
            "history screen state items=${items.size} contentItems=${contentItems.size} " +
                "quickItems=${quickFilter?.items?.size ?: -1} listMode=$listMode refreshing=$isRefreshing " +
                "selected=${selectedItemsIds.size} continue=$showContinueReadingButton " +
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
        items = contentItems,
        listMode = listMode,
        isRefreshing = isRefreshing,
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
                showContinueReadingButton = showContinueReadingButton && selectedItemsIds.isEmpty(),
                onContinueReadingClick = onContinueReadingClick,
                onQuickFilterOptionClick = onQuickFilterOptionClick,
            )
        },
    )
}

@Composable
private fun HistoryHeader(
    quickFilter: QuickFilter?,
    isStatsEnabled: Boolean,
    onStatsClick: () -> Unit,
    showContinueReadingButton: Boolean,
    onContinueReadingClick: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isStatsEnabled) {
                AssistChip(
                    onClick = onStatsClick,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_bar_chart),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    },
                    label = { Text(stringResource(R.string.statistics)) }
                )
            }
            if (showContinueReadingButton) {
                AssistChip(
                    onClick = onContinueReadingClick,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_read),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    },
                    label = { Text(stringResource(R.string._continue)) },
                )
            }
        }

        if (quickFilter != null) {
            org.skepsun.kototoro.list.ui.compose.QuickFilterSection(
                quickFilter = quickFilter.withMacroOptionsFirst(),
                onQuickFilterOptionClick = onQuickFilterOptionClick,
            )
        }
    }
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
