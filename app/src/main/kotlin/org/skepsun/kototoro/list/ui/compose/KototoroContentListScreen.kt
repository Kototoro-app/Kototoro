package org.skepsun.kototoro.list.ui.compose

import coil3.compose.AsyncImage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.widgets.ChipsView
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.VerticalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.resolveSourceTitleForUi
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.InfoModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter

private const val LoadMoreVisibleThreshold = 4
private const val GridColumnMinFitRatio = 0.94f
private val QuickFilterChipHeight = 32.dp
private val QuickFilterChipIconSize = 16.dp
private val GridHorizontalPadding = AppLayoutTokens.compactItemHorizontalPadding

private data class ContentListScreenPrefs(
    val showSourceOnCards: Boolean,
    val cardUiPrefs: ContentCardUiPrefs,
)

@Composable
private fun LoadMoreOnNearEndEffect(
    state: LazyGridState,
    enabled: Boolean,
    visibleThreshold: Int,
    onLoadMore: () -> Unit,
) {
    val loadMoreItemCount by remember(state, enabled, visibleThreshold) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val totalItemsCount = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            if (enabled && totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - visibleThreshold) {
                totalItemsCount
            } else {
                -1
            }
        }
    }
    var lastRequestedItemCount by remember(enabled) { mutableIntStateOf(-1) }
    LaunchedEffect(loadMoreItemCount) {
        if (loadMoreItemCount > 0 && loadMoreItemCount != lastRequestedItemCount) {
            lastRequestedItemCount = loadMoreItemCount
            onLoadMore()
        }
    }
}

@Composable
private fun LoadMoreOnNearEndEffect(
    state: LazyListState,
    enabled: Boolean,
    visibleThreshold: Int,
    onLoadMore: () -> Unit,
) {
    val loadMoreItemCount by remember(state, enabled, visibleThreshold) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val totalItemsCount = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            if (enabled && totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - visibleThreshold) {
                totalItemsCount
            } else {
                -1
            }
        }
    }
    var lastRequestedItemCount by remember(enabled) { mutableIntStateOf(-1) }
    LaunchedEffect(loadMoreItemCount) {
        if (loadMoreItemCount > 0 && loadMoreItemCount != lastRequestedItemCount) {
            lastRequestedItemCount = loadMoreItemCount
            onLoadMore()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroContentListScreen(
    items: List<ListModel>,
    listMode: ListMode,
    isRefreshing: Boolean,
    pullRefreshEnabled: Boolean = true,
    showRemoveOption: Boolean = false,
    sharedTransitionEnabled: Boolean = true,
    sharedElementInstanceKey: String? = null,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    hasMoreItems: Boolean = true,
    loadMoreVisibleThreshold: Int = LoadMoreVisibleThreshold,
    gridScale: Float,
    selectedItemsIds: Set<Long>,
    onPrepareItemTransition: (ContentListModel, Rect?) -> Unit = { _, _ -> },
    onItemClick: (ContentListModel) -> Unit,
    onItemLongClick: (ContentListModel) -> Unit,
    onClearSelection: () -> Unit,
    onSelectionAction: (SelectionAction) -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit = {},
    onEmptyActionClick: () -> Unit = {},
    onRetry: () -> Unit = {},
    showInlineSelectionTopBar: Boolean = true,
    showQuickFilterInline: Boolean = true,
    enableItemAnimations: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listHeader: (@Composable () -> Unit)? = null,
    gridState: LazyGridState? = null,
    listState: LazyListState? = null,
    detailedListState: LazyListState? = null,
) {
    val canLoadMore = remember(items, hasMoreItems) {
        hasMoreItems && items.any { it is ContentListModel }
    }
    val context = LocalContext.current
    val settings = androidx.compose.runtime.remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs = settings.observeAsState(
        AppSettings.KEY_SHOW_SOURCE_ON_CARDS,
        AppSettings.KEY_BADGES_TOP_LEFT,
        AppSettings.KEY_BADGES_TOP_RIGHT,
        AppSettings.KEY_BADGES_BOTTOM_LEFT,
        AppSettings.KEY_BADGES_BOTTOM_RIGHT,
        AppSettings.KEY_SHOW_EXTRA_INFO_ON_CARDS,
    ) {
        ContentListScreenPrefs(
            showSourceOnCards = isShowSourceOnCards,
            cardUiPrefs = ContentCardUiPrefs(
                badgesTopLeft = badgesTopLeft,
                badgesTopRight = badgesTopRight,
                badgesBottomLeft = badgesBottomLeft,
                badgesBottomRight = badgesBottomRight,
                showExtraInfo = showExtraInfoOnCards,
            ),
        )
    }.value
    val showSourceOnCards = screenPrefs.showSourceOnCards
    val isVerticalCardListAnimationEnabled = false
    val cardUiPrefs = screenPrefs.cardUiPrefs

    val topBarInset = contentPadding.calculateTopPadding()
    val innerPadding = remember(contentPadding, topBarInset) {
        PaddingValues(
            top = topBarInset,
            bottom = contentPadding.calculateBottomPadding(),
            start = contentPadding.calculateLeftPadding(LayoutDirection.Ltr),
            end = contentPadding.calculateRightPadding(LayoutDirection.Ltr),
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        KototoroPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            enabled = pullRefreshEnabled,
            indicatorTopInset = innerPadding,
        ) {
            if (items.isEmpty() && !isRefreshing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.nothing_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                when (listMode) {
                    ListMode.GRID,
                    ListMode.COMPACT_GRID -> {
                        val posterStyle = compactPosterCardStyle(gridScale)
                        val actualGridState = gridState ?: rememberLazyGridState()
                        LoadMoreOnNearEndEffect(
                            state = actualGridState,
                            enabled = canLoadMore,
                            visibleThreshold = loadMoreVisibleThreshold,
                            onLoadMore = onLoadMore,
                        )
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val gridSpacing = 6.dp
                            val innerHorizontalPadding = innerPadding.calculateLeftPadding(LayoutDirection.Ltr) +
                                innerPadding.calculateRightPadding(LayoutDirection.Ltr)
                            val horizontalPadding = innerHorizontalPadding + GridHorizontalPadding * 2
                            val availableWidth = (maxWidth - horizontalPadding).coerceAtLeast(1.dp)
                            val gridColumns = remember(availableWidth, posterStyle.itemWidth, gridSpacing) {
                                resolveGridColumnCount(
                                    availableWidth = availableWidth,
                                    targetItemWidth = posterStyle.itemWidth,
                                    spacing = gridSpacing,
                                )
                            }
                            val effectivePosterStyle = remember(availableWidth, gridColumns, gridSpacing, posterStyle) {
                                resolveGridPosterCardStyle(
                                    baseStyle = posterStyle,
                                    availableWidth = availableWidth,
                                    columns = gridColumns,
                                    spacing = gridSpacing,
                                )
                            }
                            val gridContentPadding = PaddingValues(
                                start = innerPadding.calculateLeftPadding(LayoutDirection.Ltr) + GridHorizontalPadding,
                                top = innerPadding.calculateTopPadding(),
                                end = innerPadding.calculateRightPadding(LayoutDirection.Ltr) + GridHorizontalPadding,
                                bottom = innerPadding.calculateBottomPadding(),
                            )

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(gridColumns),
                                state = actualGridState,
                                contentPadding = gridContentPadding,
                                horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (listHeader != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalBleed(GridHorizontalPadding),
                                        ) {
                                            listHeader()
                                        }
                                    }
                                }

                                items(
                                    count = items.size,
                                    key = { index -> listModelComposeKey(items[index], index) },
                                    span = { index ->
                                        val listModel = items[index]
                                        if (listModel is ContentGridModel) {
                                            GridItemSpan(1)
                                        } else {
                                            GridItemSpan(maxLineSpan)
                                        }
                                    },
                                    contentType = { index ->
                                        if (items[index] is ContentGridModel) "grid_card" else "supplementary"
                                    },
                                ) { index ->
                                    val listModel = items[index]
                                    if (listModel is ContentGridModel) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.TopCenter,
                                        ) {
                                            KototoroContentCardGrid(
                                                item = listModel,
                                                isSelected = listModel.id in selectedItemsIds,
                                                onClick = { coverBounds ->
                                                    onPrepareItemTransition(listModel, coverBounds)
                                                    onItemClick(listModel)
                                                },
                                                onLongClick = { onItemLongClick(listModel) },
                                                sharedTransitionEnabled = sharedTransitionEnabled,
                                                sharedElementInstanceKey = sharedElementInstanceKey,
                                                showSourceInfo = showSourceOnCards,
                                                gridScale = gridScale,
                                                cardStyle = effectivePosterStyle,
                                                compactOverlay = listMode == ListMode.COMPACT_GRID,
                                                cellContentPadding = PaddingValues(0.dp),
                                                uiPrefs = cardUiPrefs,
                                                modifier = Modifier.width(effectivePosterStyle.itemWidth),
                                            )
                                        }
                                    } else {
                                        SupplementaryListItem(
                                            item = listModel,
                                            listMode = listMode,
                                            gridScale = gridScale,
                                            horizontalBleed = GridHorizontalPadding,
                                            onQuickFilterOptionClick = onQuickFilterOptionClick,
                                            showQuickFilterInline = showQuickFilterInline,
                                            onEmptyActionClick = onEmptyActionClick,
                                            onRetry = onRetry,
                                        )
                                    }

                                }
                            }
                        }
                    }
                    ListMode.LIST -> {
                        val actualListState = listState ?: rememberLazyListState()
                        LoadMoreOnNearEndEffect(
                            state = actualListState,
                            enabled = canLoadMore,
                            visibleThreshold = loadMoreVisibleThreshold,
                            onLoadMore = onLoadMore,
                        )
                        LazyColumn(
                            state = actualListState,
                            contentPadding = innerPadding,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (listHeader != null) {
                                item {
                                    listHeader()
                                }
                            }
                            items(
                                count = items.size,
                                key = { index -> listModelComposeKey(items[index], index) },
                                contentType = { index ->
                                    if (items[index] is ContentCompactListModel) "list_card" else "supplementary"
                                },
                            ) { index ->
                                val listModel = items[index]
                                VerticalRailAnimatedVisibility(
                                    animationKey = listModelComposeKey(listModel, index),
                                    index = index,
                                    listState = actualListState,
                                    isAnimationEnabled = isVerticalCardListAnimationEnabled,
                                ) { animatedModifier ->
                                    if (listModel is ContentCompactListModel) {
                                        KototoroContentCardList(
                                            item = listModel,
                                            isSelected = listModel.id in selectedItemsIds,
                                            sharedTransitionEnabled = sharedTransitionEnabled,
                                            sharedElementInstanceKey = sharedElementInstanceKey,
                                            uiPrefs = cardUiPrefs,
                                            onClick = { coverBounds ->
                                                onPrepareItemTransition(listModel, coverBounds)
                                                onItemClick(listModel)
                                            },
                                            onLongClick = { onItemLongClick(listModel) },
                                            modifier = animatedModifier,
                                        )
                                    } else {
                                        Box(modifier = animatedModifier) {
                                            SupplementaryListItem(
                                                item = listModel,
                                                listMode = listMode,
                                                gridScale = gridScale,
                                                onQuickFilterOptionClick = onQuickFilterOptionClick,
                                                showQuickFilterInline = showQuickFilterInline,
                                                onEmptyActionClick = onEmptyActionClick,
                                                onRetry = onRetry,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ListMode.DETAILED_LIST -> {
                        val actualListState = detailedListState ?: rememberLazyListState()
                        LoadMoreOnNearEndEffect(
                            state = actualListState,
                            enabled = canLoadMore,
                            visibleThreshold = loadMoreVisibleThreshold,
                            onLoadMore = onLoadMore,
                        )
                        LazyColumn(
                            state = actualListState,
                            contentPadding = innerPadding,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (listHeader != null) {
                                item {
                                    listHeader()
                                }
                            }
                            items(
                                count = items.size,
                                key = { index -> listModelComposeKey(items[index], index) },
                                contentType = { index ->
                                    if (items[index] is ContentDetailedListModel) "detailed_card" else "supplementary"
                                },
                            ) { index ->
                                val listModel = items[index]
                                VerticalRailAnimatedVisibility(
                                    animationKey = listModelComposeKey(listModel, index),
                                    index = index,
                                    listState = actualListState,
                                    isAnimationEnabled = isVerticalCardListAnimationEnabled,
                                ) { animatedModifier ->
                                    if (listModel is ContentDetailedListModel) {
                                        KototoroContentCardDetailedList(
                                            item = listModel,
                                            isSelected = listModel.id in selectedItemsIds,
                                            sharedTransitionEnabled = sharedTransitionEnabled,
                                            sharedElementInstanceKey = sharedElementInstanceKey,
                                            uiPrefs = cardUiPrefs,
                                            onClick = { coverBounds ->
                                                onPrepareItemTransition(listModel, coverBounds)
                                                onItemClick(listModel)
                                            },
                                            onLongClick = { onItemLongClick(listModel) },
                                            modifier = animatedModifier,
                                        )
                                    } else {
                                        Box(modifier = animatedModifier) {
                                            SupplementaryListItem(
                                                item = listModel,
                                                listMode = listMode,
                                                gridScale = gridScale,
                                                onQuickFilterOptionClick = onQuickFilterOptionClick,
                                                showQuickFilterInline = showQuickFilterInline,
                                                onEmptyActionClick = onEmptyActionClick,
                                                onRetry = onRetry,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Selection Contextual TopBar overlay
        AnimatedVisibility(
            visible = showInlineSelectionTopBar && selectedItemsIds.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val selectedModels = items.mapNotNull { it as? ContentListModel }.filter { it.id in selectedItemsIds }
            val isAllNonLocal = selectedModels.none { it.manga.isLocal }

            KototoroSelectionTopBar(
                selectedCount = selectedItemsIds.size,
                isAllNonLocal = isAllNonLocal,
                isSingleSelection = selectedItemsIds.size == 1,
                showRemoveOption = showRemoveOption,
                onClearSelection = onClearSelection,
                onActionClick = onSelectionAction
            )
        }
    }
}

internal fun resolveGridPosterCardStyle(
    baseStyle: CompactPosterCardStyle,
    availableWidth: androidx.compose.ui.unit.Dp,
    columns: Int,
    spacing: androidx.compose.ui.unit.Dp,
): CompactPosterCardStyle {
    val safeColumns = columns.coerceAtLeast(1)
    val cellWidth = (
        availableWidth.value -
            spacing.value * (safeColumns - 1)
        ).coerceAtLeast(1f) / safeColumns
    val stretchedWidth = cellWidth.dp
    if (stretchedWidth == baseStyle.itemWidth) {
        return baseStyle
    }
    val scale = stretchedWidth.value / baseStyle.itemWidth.value
    return baseStyle.copy(
        itemWidth = stretchedWidth,
        posterHeight = (baseStyle.posterHeight.value * scale).dp,
    )
}

internal fun resolveGridColumnCount(
    availableWidth: androidx.compose.ui.unit.Dp,
    targetItemWidth: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
): Int {
    val minCellWidth = targetItemWidth.value * GridColumnMinFitRatio
    return floor(
        ((availableWidth.value + spacing.value) / (minCellWidth + spacing.value)).toDouble(),
    ).toInt().coerceAtLeast(1)
}

private fun Modifier.horizontalBleed(horizontal: androidx.compose.ui.unit.Dp): Modifier {
    if (horizontal <= 0.dp) return this
    return layout { measurable, constraints ->
        val bleedPx = horizontal.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(maxWidth = constraints.maxWidth + bleedPx * 2),
        )
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(-bleedPx, 0)
        }
    }
}

@Composable
private fun SupplementaryListItem(
    item: ListModel,
    listMode: ListMode,
    gridScale: Float,
    horizontalBleed: androidx.compose.ui.unit.Dp = 0.dp,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    showQuickFilterInline: Boolean,
    onEmptyActionClick: () -> Unit,
    onRetry: () -> Unit,
) {
    when (item) {
        is ListHeader -> ListHeaderItem(item)
        is QuickFilter -> if (showQuickFilterInline) {
            QuickFilterSection(
                quickFilter = item,
                onQuickFilterOptionClick = onQuickFilterOptionClick,
                modifier = Modifier.horizontalBleed(horizontalBleed),
            )
        }
        is InfoModel -> InfoCard(item)
        is EmptyState -> EmptyStateCard(item, onEmptyActionClick)
        is ErrorState -> ErrorStateCard(item, onRetry)
        LoadingState -> LoadingStateItem(listMode = listMode, gridScale = gridScale)
    }
}

@Composable
private fun ListHeaderItem(item: ListHeader) {
    val context = LocalContext.current
    val title = item.getText(context)?.toString().orEmpty()
    if (title.isBlank()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.buttonTextRes != 0) {
            TextButton(onClick = {}) {
                Text(stringResource(item.buttonTextRes))
            }
        }
    }
}

@Composable
fun QuickFilterSection(
    quickFilter: QuickFilter,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val listState = rememberLazyListState()
    val entryPoint = remember(context.applicationContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BaseApp.BaseAppEntryPoint::class.java,
            )
        }.getOrNull()
    }
    val orderedChips = remember(quickFilter.items) {
        quickFilter.items.sortedBy { chip -> !chip.isChecked }
    }
    LaunchedEffect(orderedChips) {
        if (orderedChips.firstOrNull()?.isChecked == true && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = AppLayoutTokens.sectionHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items = orderedChips,
            key = { chip ->
                val option = chip.data as? ListFilterOption
                option?.let { "${it::class.qualifiedName}:${it.hashCode()}" } ?: chip.hashCode()
            },
            contentType = { "filter_chip" },
        ) { chip ->
            val option = chip.data as? ListFilterOption
            val contentColor = when {
                isIosStyle && chip.isChecked -> MaterialTheme.colorScheme.inverseOnSurface
                chip.isChecked -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val chipShape = RoundedCornerShape(999.dp)
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Row(
                    modifier = Modifier
                        .then(
                            if (isIosStyle) {
                                Modifier
                                    .background(
                                        color = if (chip.isChecked) {
                                            MaterialTheme.colorScheme.inverseSurface
                                        } else {
                                            Color.Transparent
                                        },
                                        shape = chipShape,
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (chip.isChecked) {
                                            MaterialTheme.colorScheme.inverseSurface
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = chipShape,
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (option != null) {
                                Modifier.clickable { onQuickFilterOptionClick(option) }
                            } else {
                                Modifier
                            },
                        )
                        .height(QuickFilterChipHeight)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        chipIcon(chip)?.invoke()
                        Text(
                            text = buildChipLabel(context, chip, entryPoint),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            fontWeight = if (chip.isChecked) {
                                androidx.compose.ui.text.font.FontWeight.SemiBold
                            } else {
                                androidx.compose.ui.text.font.FontWeight.Normal
                            },
                            maxLines = 1,
                        )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(item: InfoModel) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(item.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(item.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    item: EmptyState,
    onEmptyActionClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (item.icon != 0) {
            Icon(
                painter = painterResource(item.icon),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val titleText = item.textPrimaryText?.toString()
        Text(
            text = titleText ?: stringResource(item.textPrimary),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val subtitleText = item.textSecondaryText?.toString()
        if (item.textSecondary != 0 || !subtitleText.isNullOrBlank()) {
            Text(
                text = subtitleText ?: stringResource(item.textSecondary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.actionStringRes != 0) {
            Button(onClick = onEmptyActionClick) {
                Text(stringResource(item.actionStringRes))
            }
        }
    }
}

@Composable
private fun ErrorStateCard(
    item: ErrorState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(if (item.icon != 0) item.icon else R.drawable.ic_error_large),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.error_occurred),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = item.exception.localizedMessage ?: item.exception.javaClass.simpleName.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.canRetry) {
            Button(onClick = onRetry) {
                Text(stringResource(item.buttonText.takeIf { it != 0 } ?: R.string.retry))
            }
        }
    }
}

@Composable
private fun LoadingStateItem(
    listMode: ListMode,
    gridScale: Float,
) {
    when (listMode) {
        ListMode.GRID,
        ListMode.COMPACT_GRID -> GridLoadingSkeleton(gridScale = gridScale)
        ListMode.LIST,
        ListMode.DETAILED_LIST -> LinearLoadingSkeleton(isDetailed = listMode == ListMode.DETAILED_LIST)
    }
}

@Composable
private fun GridLoadingSkeleton(
    gridScale: Float,
) {
    val posterStyle = compactPosterCardStyle(gridScale)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(posterStyle.posterHeight)
                )
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(12.dp)
                )
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .height(12.dp)
                )
            }
        }
    }
}

@Composable
private fun LinearLoadingSkeleton(
    isDetailed: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(if (isDetailed) 96.dp else 84.dp)
                        .height(if (isDetailed) 132.dp else 116.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(14.dp)
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(12.dp)
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .height(12.dp)
                    )
                    if (isDetailed) {
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.58f)
                                .height(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.medium,
            ),
    )
}

@Composable
private fun chipIcon(chip: ChipsView.ChipModel): (@Composable () -> Unit)? {
    if (chip.isChecked) {
        return {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(QuickFilterChipIconSize),
            )
        }
    }
    if (chip.iconData == null && chip.icon == 0) {
        return null
    }
    return {
        if (chip.iconData != null) {
            AsyncImage(
                model = chip.iconData,
                contentDescription = null,
                placeholder = painterResource(chip.icon.takeIf { it != 0 } ?: com.google.android.material.R.drawable.navigation_empty_icon),
                error = painterResource(chip.icon.takeIf { it != 0 } ?: com.google.android.material.R.drawable.navigation_empty_icon),
                modifier = Modifier.size(QuickFilterChipIconSize),
            )
        } else {
            Icon(
                painter = painterResource(chip.icon),
                contentDescription = null,
                tint = if (chip.tint == 0) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(QuickFilterChipIconSize),
            )
        }
    }
}

private fun listModelComposeKey(
    listModel: ListModel,
    index: Int,
): String = when (listModel) {
    is ContentListModel -> buildString {
        append(listModel.javaClass.simpleName)
        append(':')
        append(listModel.source.name)
        append(':')
        append(listModel.id)
        append(':')
        append(
            listModel.manga.url
                .ifBlank { listModel.manga.publicUrl }
                .ifBlank { listModel.title },
        )
        append(':')
        append(index)
    }
    is ListHeader -> "header:${listModel.hashCode()}:$index"
    is QuickFilter -> "quick_filter:${listModel.hashCode()}:$index"
    is InfoModel -> "info:${listModel.hashCode()}:$index"
    is EmptyState -> "empty_state:${listModel.hashCode()}:$index"
    is ErrorState -> "error_state:${listModel.hashCode()}:$index"
    LoadingState -> "loading_state:$index"
    else -> "${listModel.javaClass.name}:${listModel.hashCode()}:$index"
}

@Composable
private fun buildChipLabel(
    context: android.content.Context,
    chip: ChipsView.ChipModel,
    entryPoint: BaseApp.BaseAppEntryPoint?,
): String {
    val title = when {
        chip.titleResId != 0 -> stringResource(chip.titleResId)
        else -> resolveFilterChipTitle(context, chip, entryPoint)
    }
    return if (chip.counter > 0) {
        "$title ${chip.counter}"
    } else {
        title
    }
}

private fun resolveFilterChipTitle(
    context: android.content.Context,
    chip: ChipsView.ChipModel,
    entryPoint: BaseApp.BaseAppEntryPoint?,
): String {
    val sourceOption = chip.data as? ListFilterOption.Source
    if (sourceOption != null) {
        return resolveSourceTitleForUi(
            context = context,
            source = sourceOption.mangaSource,
            entryPoint = entryPoint,
        )
    }
    return chip.title?.toString().orEmpty()
}
