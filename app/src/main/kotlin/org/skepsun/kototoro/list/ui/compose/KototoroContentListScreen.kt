package org.skepsun.kototoro.list.ui.compose

import coil3.compose.AsyncImage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import org.skepsun.kototoro.core.ui.theme.popupMenuContainerColor
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.VerticalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.compose.resolveSourceTitleForUi
import org.skepsun.kototoro.core.ui.compose.ScrollToTopEffect
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.util.ext.getCauseUrl
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
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
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.QuickFilterGroup
import kotlinx.coroutines.flow.first

private const val LoadMoreVisibleThreshold = 4
private const val GridColumnMinFitRatio = 0.94f
private val QuickFilterChipHeight = 32.dp
private val QuickFilterChipIconSize = 16.dp
internal val GridHorizontalPadding = AppLayoutTokens.compactItemHorizontalPadding
internal val GridSpacing = 8.dp

private data class ContentListScreenPrefs(
    val showSourceOnCards: Boolean,
    val cardUiPrefs: ContentCardUiPrefs,
)

private suspend fun scrollToTopAfterPagingSettles(
    pagingItems: LazyPagingItems<ListModel>?,
    scrollToTop: suspend () -> Unit,
) {
    scrollToTop()
    if (pagingItems == null) return
    snapshotFlow {
        val loadState = pagingItems.loadState
        loadState.refresh !is LoadState.Loading &&
            loadState.append !is LoadState.Loading &&
            loadState.prepend !is LoadState.Loading
    }.first { isIdle -> isIdle }
    withFrameNanos { }
    scrollToTop()
}

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
    pagingItems: LazyPagingItems<ListModel>? = null,
    listMode: ListMode,
    isRefreshing: Boolean,
    pullRefreshEnabled: Boolean = true,
    showScrollbar: Boolean = true,
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
    onSecondaryAction: ((Throwable) -> Unit)? = null,
    showInlineSelectionTopBar: Boolean = true,
    inlineSelectionBarAnimated: Boolean = true,
    inlineSelectionSupportedActions: Set<SelectionAction>? = null,
    inlineSelectionIncludeContextualActions: Boolean = true,
    showQuickFilterInline: Boolean = true,
    quickFilterLeadingContent: (@Composable () -> Unit)? = null,
    enableItemAnimations: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listHeader: (@Composable () -> Unit)? = null,
    gridState: LazyGridState? = null,
    listState: LazyListState? = null,
    detailedListState: LazyListState? = null,
    highlightedItemId: Long? = null,
) {
    val leadingItemCount = if (pagingItems == null) 0 else items.size
    val pagingItemCount = pagingItems?.itemCount ?: 0
    val combinedIndex = remember(items.size, pagingItems != null, pagingItemCount) {
        CombinedContentListIndex(
            leadingCount = if (pagingItems == null) items.size else leadingItemCount,
            pagingCount = pagingItemCount,
        )
    }
    val itemCount = combinedIndex.itemCount
    val pagingRefreshState = pagingItems?.loadState?.refresh
    fun peekItem(index: Int): ListModel? {
        if (pagingItems == null) {
            return items.getOrNull(index)
        }
        val pagingSnapshot = pagingItems.itemSnapshotList
        return when (val origin = combinedIndex.origin(index, pagingSnapshot?.size ?: 0)) {
            is ContentListItemOrigin.Leading -> items[origin.index]
            is ContentListItemOrigin.Paging -> pagingSnapshot?.get(origin.index)
            ContentListItemOrigin.OutOfBounds -> null
        }
    }
    fun getItem(index: Int): ListModel? {
        if (pagingItems == null) {
            return items.getOrNull(index)
        }
        val currentPagingItems = pagingItems
        return when (val origin = combinedIndex.origin(index, currentPagingItems.itemCount)) {
            is ContentListItemOrigin.Leading -> items[origin.index]
            is ContentListItemOrigin.Paging -> currentPagingItems.getDuringSnapshotChangeOrNull(origin.index)
            ContentListItemOrigin.OutOfBounds -> null
        }
    }
    fun itemDescriptor(index: Int): ContentListItemDescriptor =
        contentListItemDescriptor(peekItem(index), index)
    fun itemKey(index: Int): Any = contentListItemKey(peekItem(index), index)
    fun itemContentType(index: Int): ContentListItemType = contentListItemContentType(peekItem(index))
    val canLoadMore = remember(items, pagingItems?.itemCount, hasMoreItems) {
        pagingItems == null && hasMoreItems && items.any { it is ContentListModel }
    }
    val context = LocalContext.current
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val pagingSnapshotItems = pagingItems?.itemSnapshotList?.items
    val firstContentIndex = remember(itemCount, items, pagingSnapshotItems) {
        (0 until itemCount).firstOrNull { index -> peekItem(index) is ContentListModel }
    }
    var focusedContentId by rememberSaveable { mutableStateOf<Long?>(null) }
    val fallbackContentId = firstContentIndex?.let { index -> (peekItem(index) as? ContentListModel)?.id }
    val focusedContentIndex = remember(itemCount, items, pagingSnapshotItems, focusedContentId) {
        focusedContentId?.let { focusedId ->
            (0 until itemCount).firstOrNull { index ->
                (peekItem(index) as? ContentListModel)?.id == focusedId
            }
        }
    }
    val focusTargetIndex = focusedContentIndex ?: firstContentIndex
    val focusTargetId = focusTargetIndex?.let { index -> (peekItem(index) as? ContentListModel)?.id }
        ?: fallbackContentId
    val firstContentFocusRequester = remember { FocusRequester() }
    // Moving between cards updates focusTargetId; it must not schedule another focus request.
    LaunchedEffect(isTvPresentation, focusTargetId != null, listMode) {
        if (isTvPresentation && focusTargetId != null) {
            withFrameNanos { }
            runCatching { firstContentFocusRequester.requestFocus() }
        }
    }
    val defaultSecondaryAction: (Throwable) -> Unit = remember(context) {
        { error ->
            error.getCauseUrl()?.let { url ->
                (context as? FragmentActivity)?.let { AppRouter(it).openBrowser(url, null, null) }
            }
        }
    }
    val secondaryAction = onSecondaryAction ?: defaultSecondaryAction
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
            if (pagingItemCount == 0 && pagingRefreshState is LoadState.Loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KototoroLoadingIndicator()
                }
            } else if (pagingItemCount == 0 && pagingRefreshState is LoadState.Error) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorStateCard(
                        item = pagingRefreshState.error.toErrorState(),
                        onRetry = onRetry,
                        onSecondaryAction = secondaryAction,
                    )
                }
            } else if (itemCount == 0 && !isRefreshing && listHeader == null) {
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
                        ScrollToTopEffect {
                            scrollToTopAfterPagingSettles(pagingItems) {
                                actualGridState.scrollToItem(0)
                            }
                        }
                        LoadMoreOnNearEndEffect(
                            state = actualGridState,
                            enabled = canLoadMore,
                            visibleThreshold = loadMoreVisibleThreshold,
                            onLoadMore = onLoadMore,
                        )
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val gridSpacing = GridSpacing
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
                                verticalArrangement = Arrangement.spacedBy(gridSpacing),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
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
                                    count = itemCount,
                                    key = ::itemKey,
                                    span = { index ->
                                        val listModel = peekItem(index)
                                        if (listModel is ContentGridModel) {
                                            GridItemSpan(1)
                                        } else {
                                            GridItemSpan(maxLineSpan)
                                        }
                                    },
                                    contentType = ::itemContentType,
                                ) { index ->
                                    val listModel = getItem(index) ?: return@items
                                    if (listModel is ContentGridModel) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.TopCenter,
                                        ) {
                                            KototoroContentCardGrid(
                                                item = listModel,
                                                isSelected = listModel.id in selectedItemsIds,
                                                isHighlighted = listModel.id == highlightedItemId,
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
                                                focusRequester = firstContentFocusRequester.takeIf {
                                                    isTvPresentation && listModel.id == focusTargetId
                                                },
                                                onFocused = { focusedContentId = listModel.id },
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
                                            leadingContent = quickFilterLeadingContent,
                                            onEmptyActionClick = onEmptyActionClick,
                                            onRetry = onRetry,
                                            onSecondaryAction = secondaryAction,
                                        )
                                    }

                                }
                            }
                            if (showScrollbar) {
                                VerticalScrollbar(
                                    state = actualGridState,
                                    contentPadding = innerPadding,
                                )
                            }
                        }
                    }
                    ListMode.LIST -> {
                        val actualListState = listState ?: rememberLazyListState()
                        ScrollToTopEffect {
                            scrollToTopAfterPagingSettles(pagingItems) {
                                actualListState.scrollToItem(0)
                            }
                        }
                        LoadMoreOnNearEndEffect(
                            state = actualListState,
                            enabled = canLoadMore,
                            visibleThreshold = loadMoreVisibleThreshold,
                            onLoadMore = onLoadMore,
                        )
                        LazyColumn(
                            state = actualListState,
                            contentPadding = innerPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
                        ) {
                            if (listHeader != null) {
                                item {
                                    listHeader()
                                }
                            }
                            items(
                                count = itemCount,
                                key = ::itemKey,
                                contentType = ::itemContentType,
                            ) { index ->
                                val listModel = getItem(index) ?: return@items
                                VerticalRailAnimatedVisibility(
                                    animationKey = itemKey(index),
                                    index = index,
                                    listState = actualListState,
                                    isAnimationEnabled = isVerticalCardListAnimationEnabled,
                                ) { animatedModifier ->
                                    if (listModel is ContentCompactListModel) {
                                        KototoroContentCardList(
                                            item = listModel,
                                            isSelected = listModel.id in selectedItemsIds,
                                            isHighlighted = listModel.id == highlightedItemId,
                                            sharedTransitionEnabled = sharedTransitionEnabled,
                                            sharedElementInstanceKey = sharedElementInstanceKey,
                                            uiPrefs = cardUiPrefs,
                                            focusRequester = firstContentFocusRequester.takeIf {
                                                isTvPresentation && listModel.id == focusTargetId
                                            },
                                            onFocused = { focusedContentId = listModel.id },
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
                                                leadingContent = quickFilterLeadingContent,
                                                onEmptyActionClick = onEmptyActionClick,
                                                onRetry = onRetry,
                                                onSecondaryAction = secondaryAction,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (showScrollbar) {
                            VerticalScrollbar(
                                state = actualListState,
                                contentPadding = innerPadding,
                            )
                        }
                    }
                    ListMode.DETAILED_LIST -> {
                        val actualListState = detailedListState ?: rememberLazyListState()
                        ScrollToTopEffect {
                            scrollToTopAfterPagingSettles(pagingItems) {
                                actualListState.scrollToItem(0)
                            }
                        }
                        LoadMoreOnNearEndEffect(
                            state = actualListState,
                            enabled = canLoadMore,
                            visibleThreshold = loadMoreVisibleThreshold,
                            onLoadMore = onLoadMore,
                        )
                        LazyColumn(
                            state = actualListState,
                            contentPadding = innerPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
                        ) {
                            if (listHeader != null) {
                                item {
                                    listHeader()
                                }
                            }
                            items(
                                count = itemCount,
                                key = ::itemKey,
                                contentType = ::itemContentType,
                            ) { index ->
                                val listModel = getItem(index) ?: return@items
                                VerticalRailAnimatedVisibility(
                                    animationKey = itemKey(index),
                                    index = index,
                                    listState = actualListState,
                                    isAnimationEnabled = isVerticalCardListAnimationEnabled,
                                ) { animatedModifier ->
                                    if (listModel is ContentDetailedListModel) {
                                        KototoroContentCardDetailedList(
                                            item = listModel,
                                            isSelected = listModel.id in selectedItemsIds,
                                            isHighlighted = listModel.id == highlightedItemId,
                                            sharedTransitionEnabled = sharedTransitionEnabled,
                                            sharedElementInstanceKey = sharedElementInstanceKey,
                                            uiPrefs = cardUiPrefs,
                                            focusRequester = firstContentFocusRequester.takeIf {
                                                isTvPresentation && listModel.id == focusTargetId
                                            },
                                            onFocused = { focusedContentId = listModel.id },
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
                                                leadingContent = quickFilterLeadingContent,
                                                onEmptyActionClick = onEmptyActionClick,
                                                onRetry = onRetry,
                                                onSecondaryAction = secondaryAction,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (showScrollbar) {
                            VerticalScrollbar(
                                state = actualListState,
                                contentPadding = innerPadding,
                            )
                        }
                    }
                }
            }
        }

        // Selection Contextual TopBar overlay
        AnimatedVisibility(
            visible = showInlineSelectionTopBar && selectedItemsIds.isNotEmpty(),
            enter = if (inlineSelectionBarAnimated) {
                slideInVertically(initialOffsetY = { -it })
            } else {
                EnterTransition.None
            },
            exit = if (inlineSelectionBarAnimated) {
                slideOutVertically(targetOffsetY = { -it })
            } else {
                ExitTransition.None
            },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val selectedModels = (pagingItems?.itemSnapshotList?.items ?: items)
                .mapNotNull { it as? ContentListModel }
                .filter { it.id in selectedItemsIds }
            val isAllNonLocal = selectedModels.none { it.manga.isLocal }

            KototoroSelectionTopBar(
                selectedCount = selectedItemsIds.size,
                isAllNonLocal = isAllNonLocal,
                isSingleSelection = selectedItemsIds.size == 1,
                showRemoveOption = showRemoveOption,
                supportedActions = inlineSelectionSupportedActions,
                includeContextualActions = inlineSelectionIncludeContextualActions,
                onClearSelection = onClearSelection,
                onActionClick = onSelectionAction
            )
        }
    }
}

private fun <T : Any> LazyPagingItems<T>.getDuringSnapshotChangeOrNull(index: Int): T? {
    if (index !in 0 until itemCount) return null
    return try {
        this[index]
    } catch (_: IndexOutOfBoundsException) {
        // Paging can publish a shorter snapshot after LazyLayout captured its previous item count.
        null
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
    leadingContent: (@Composable () -> Unit)? = null,
    onEmptyActionClick: () -> Unit,
    onRetry: () -> Unit,
    onSecondaryAction: (Throwable) -> Unit,
) {
    when (item) {
        is ListHeader -> ListHeaderItem(item, modifier = Modifier.horizontalBleed(horizontalBleed))
        is QuickFilter -> if (showQuickFilterInline) {
            QuickFilterSection(
                quickFilter = item,
                onQuickFilterOptionClick = onQuickFilterOptionClick,
                leadingContent = leadingContent,
                modifier = Modifier.horizontalBleed(horizontalBleed),
            )
        }
        is InfoModel -> InfoCard(item)
        is EmptyState -> EmptyStateCard(item, onEmptyActionClick)
        is ErrorState -> ErrorStateCard(item, onRetry, onSecondaryAction)
        LoadingState -> LoadingStateItem(listMode = listMode, gridScale = gridScale)
    }
}

@Composable
private fun ListHeaderItem(
    item: ListHeader,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val title = item.getText(context)?.toString().orEmpty()
    if (title.isBlank()) return

    // Group headers (history dates, favourites/updates section labels) sit
    // directly on the page canvas. Over the artwork backdrop they get a
    // lightweight pill so the text stays readable on any image; on plain
    // backgrounds the plain text hierarchy is kept.
    val usesArtworkBackdrop = LocalBackgroundStyle.current.usesArtworkBackdrop

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (usesArtworkBackdrop) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    leadingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val usesArtworkBackdrop = LocalBackgroundStyle.current.usesArtworkBackdrop
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
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = AppLayoutTokens.sectionHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
    ) {
        leadingContent?.let { content ->
            item(key = "quick_filter_leading_content", contentType = "quick_filter_leading_content") {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp,
                ) {
                    content()
                }
            }
        }
        items(
            items = quickFilter.groups,
            key = { group -> "filter_group:${group.key}" },
            contentType = { "filter_group" },
        ) { group ->
            QuickFilterGroupChip(
                group = group,
                isIosStyle = isIosStyle,
                context = context,
                entryPoint = entryPoint,
                onQuickFilterOptionClick = onQuickFilterOptionClick,
            )
        }
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
            // MD3 keeps its filter chips as plain text on plain backgrounds;
            // over the artwork backdrop they need a light pill so the label
            // stays readable on any image without changing the MD3 chip
            // language (no border, tonal container only).
            val chipBackgroundModifier = when {
                isIosStyle -> Modifier
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

                usesArtworkBackdrop -> Modifier.background(
                    color = if (chip.isChecked) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                    },
                    shape = chipShape,
                )

                else -> Modifier
            }
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Row(
                    modifier = Modifier
                        .then(chipBackgroundModifier)
                        .then(
                            if (option != null) {
                                Modifier
                                    .tvFocusable(shape = chipShape, addFocusTarget = false)
                                    .clickable { onQuickFilterOptionClick(option) }
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
private fun QuickFilterGroupChip(
    group: QuickFilterGroup,
    isIosStyle: Boolean,
    context: android.content.Context,
    entryPoint: BaseApp.BaseAppEntryPoint?,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val usesArtworkBackdrop = LocalBackgroundStyle.current.usesArtworkBackdrop
    val selectedItems = group.items.filter(ChipModel::isChecked)
    val isSelected = selectedItems.isNotEmpty()
    val contentColor = when {
        isIosStyle && isSelected -> MaterialTheme.colorScheme.inverseOnSurface
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val chipShape = RoundedCornerShape(999.dp)
    // Same pill rule as the plain filter chips: MD3 keeps text-only chips on
    // plain backgrounds and gains a light container only over the artwork
    // backdrop, while iOS always draws its outline/tonal container.
    val chipBackgroundModifier = when {
        isIosStyle -> Modifier
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    Color.Transparent
                },
                shape = chipShape,
            )
            .border(
                width = 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = chipShape,
            )

        usesArtworkBackdrop -> Modifier.background(
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
            },
            shape = chipShape,
        )

        else -> Modifier
    }
    Box {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                modifier = Modifier
                    .then(chipBackgroundModifier)
                    .tvFocusable(shape = chipShape, enabled = true, addFocusTarget = false)
                    .clickable { expanded = true }
                    .height(QuickFilterChipHeight)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(group.iconResId),
                    contentDescription = null,
                    modifier = Modifier.size(QuickFilterChipIconSize),
                )
                Text(
                    text = when (selectedItems.size) {
                        0 -> stringResource(group.titleResId)
                        1 -> buildChipLabel(context, selectedItems.single(), entryPoint)
                        else -> stringResource(
                            R.string.filter_group_selected_count,
                            stringResource(group.titleResId),
                            selectedItems.size,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = if (isSelected) {
                        androidx.compose.ui.text.font.FontWeight.SemiBold
                    } else {
                        androidx.compose.ui.text.font.FontWeight.Normal
                    },
                    maxLines = 1,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.size(QuickFilterChipIconSize),
                )
            }
        }
        DropdownMenu(
            containerColor = MaterialTheme.colorScheme.popupMenuContainerColor(),
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            group.items.forEach { chip ->
                val option = chip.data as? ListFilterOption ?: return@forEach
                val itemColor = if (chip.isChecked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = buildChipLabel(context, chip, entryPoint),
                            color = itemColor,
                            fontWeight = if (chip.isChecked) {
                                androidx.compose.ui.text.font.FontWeight.SemiBold
                            } else {
                                androidx.compose.ui.text.font.FontWeight.Normal
                            },
                        )
                    },
                    onClick = { onQuickFilterOptionClick(option) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (chip.isChecked) R.drawable.ic_check else chip.icon,
                            ),
                            contentDescription = null,
                            tint = itemColor,
                        )
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reset_filter)) },
                onClick = {
                    selectedItems.forEach { chip ->
                        (chip.data as? ListFilterOption)?.let(onQuickFilterOptionClick)
                    }
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_clear_all),
                        contentDescription = null,
                    )
                },
                enabled = selectedItems.isNotEmpty(),
            )
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
    onSecondaryAction: (Throwable) -> Unit,
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
        if (item.secondaryButtonText != 0) {
            TextButton(onClick = { onSecondaryAction(item.exception) }) {
                Text(stringResource(item.secondaryButtonText))
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
internal fun chipIcon(chip: ChipModel): (@Composable () -> Unit)? {
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

@Composable
internal fun buildChipLabel(
    context: android.content.Context,
    chip: ChipModel,
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
    chip: ChipModel,
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
