package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.ScrollToTopEffect
import org.skepsun.kototoro.list.ui.RetainedPagingSnapshotHost
import org.skepsun.kototoro.list.ui.compose.rememberRetainedPagingSnapshotState
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    leadingItems: List<ListModel>,
    fallbackItems: List<ListModel>,
    /** Null once the feed renders statically from [fallbackItems] (Phase F4). */
    pagingItems: LazyPagingItems<ListModel>?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onFeedItemClick: (FeedItem, Rect?) -> Unit,
    onUpdatedContentItemClick: (UpdatedContentHeaderItem, Rect?) -> Unit,
    onUpdatedContentMoreClick: (UpdatedContentHeader) -> Unit,
    categories: List<FavouriteCategory>,
    selectedCategoryId: Long,
    onCategorySelected: (Long) -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    selectedItemIds: Set<Long> = emptySet(),
    onFeedItemLongClick: (FeedItem) -> Unit = {},
    onFeedItemContinueReading: (FeedItem) -> Unit = {},
    showCategoryFilterInline: Boolean = true,
    host: RetainedPagingSnapshotHost? = null,
    modifier: Modifier = Modifier
) {
    // The paging chain is gone (Phase F4): pagingItems is null and the static
    // fallback path is the only path. The nullable shape keeps the retained
    // snapshot machinery (still shared with the history page) intact.
    val pagingRefreshState = pagingItems?.loadState?.refresh
    val showFallback = pagingItems == null || (pagingItems.itemCount == 0 && pagingRefreshState is LoadState.NotLoading)
    val liveLeadingItems = if (showFallback) leadingItems + fallbackItems else leadingItems
    val livePagingItems = pagingItems.takeUnless { showFallback }
    val retainedState = host?.let { snapshotHost ->
        rememberRetainedPagingSnapshotState(
            host = snapshotHost,
            retainEnabled = true,
            leadingItems = liveLeadingItems,
            lazyPagingItems = livePagingItems,
            listMode = ListMode.LIST,
            staticRefreshSettled = !isRefreshing && liveLeadingItems.none { it is LoadingState },
        )
    }
    val listState = retainedState?.listState ?: rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val displayedItems = retainedState?.displayedItems ?: liveLeadingItems
    val displayedPagingItems = retainedState?.displayedPagingItems ?: livePagingItems
    val pagingStatusItem: ListModel? = displayedPagingItems?.let { paging ->
        when {
            paging.itemCount == 0 && paging.loadState.refresh is LoadState.Loading -> LoadingState
            paging.itemCount == 0 && paging.loadState.refresh is LoadState.Error ->
                (paging.loadState.refresh as LoadState.Error).error.toErrorState()
            paging.loadState.append is LoadState.Loading -> LoadingState
            paging.loadState.append is LoadState.Error ->
                (paging.loadState.append as LoadState.Error).error.toErrorState()
            else -> null
        }
    }
    ScrollToTopEffect {
        listState.scrollToItem(0)
    }
    val context = LocalContext.current

    val density = LocalDensity.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val carouselPrefs by settings.observeAsState(
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_BADGES_BOTTOM_RIGHT,
    ) {
        UpdatedContentCarouselPrefs(
            gridScale = gridSize / 100f,
            badgesBottomRight = badgesBottomRight,
        )
    }

    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val captureNavigationSnapshot: (ListModel) -> Unit = { clickedItem ->
        retainedState?.let { state ->
            val pagedItems = displayedPagingItems?.itemSnapshotList?.items
            val loadedItems = pagedItems ?: displayedItems
            val pagingAnchorIndex = if (pagedItems == null) {
                state.listState.firstVisibleItemIndex
            } else {
                (state.listState.firstVisibleItemIndex - displayedItems.size).coerceAtLeast(0)
            }
            state.captureOnNavigate(
                clickedItem,
                loadedItems,
                state.listState.firstVisibleItemIndex,
                state.listState.firstVisibleItemScrollOffset,
                ListMode.LIST,
                pagingAnchorIndex,
            )
        }
    }

    KototoroPullToRefreshBox(
        isRefreshing = isRefreshing || retainedState?.pagingIsRefreshing == true,
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(categories, selectedCategoryId) {
                if (categories.size <= 1) {
                    return@pointerInput
                }
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var pointerStillDown = true
                    while (pointerStillDown) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.positionChangedIgnoreConsumed()) {
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                        }
                        pointerStillDown = event.changes.any {
                            !it.changedToUpIgnoreConsumed() && it.pressed
                        }
                    }
                    if (abs(totalX) < swipeThresholdPx || abs(totalX) <= abs(totalY) * 1.35f) {
                        return@awaitEachGesture
                    }
                    val currentIndex = categories.indexOfFirst { it.id == selectedCategoryId }
                    if (currentIndex == -1) {
                        return@awaitEachGesture
                    }
                    val targetIndex = when {
                        totalX < 0f -> (currentIndex + 1).coerceAtMost(categories.lastIndex)
                        else -> (currentIndex - 1).coerceAtLeast(0)
                    }
                    if (targetIndex != currentIndex) {
                        onCategorySelected(categories[targetIndex].id)
                    }
                }
            },
        indicatorTopInset = contentPadding,
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
                start = 0.dp,
                end = 0.dp,
            ),
            modifier = Modifier.fillMaxSize()
        ) {

            items(
                items = displayedItems,
                key = ::feedItemKey,
                contentType = ::feedItemContentType,
            ) { item ->
                FeedListItemContent(
                    item = item,
                    carouselPrefs = carouselPrefs,
                    selectedItemIds = selectedItemIds,
                    showCategoryFilterInline = showCategoryFilterInline,
                    onQuickFilterOptionClick = onQuickFilterOptionClick,
                    onFeedItemClick = onFeedItemClick,
                    onFeedItemLongClick = onFeedItemLongClick,
                    onFeedItemContinueReading = onFeedItemContinueReading,
                    onUpdatedContentItemClick = onUpdatedContentItemClick,
                    onUpdatedContentMoreClick = onUpdatedContentMoreClick,
                    onCaptureNavigationSnapshot = captureNavigationSnapshot,
                    onRetry = pagingItems?.let { it::retry } ?: {},
                )
            }
            displayedPagingItems?.let { pagedItems ->
                items(
                    count = pagedItems.itemCount,
                    key = pagedItems.itemKey(::feedItemKey),
                    contentType = pagedItems.itemContentType(::feedItemContentType),
                ) { index ->
                    val item = pagedItems[index] ?: return@items
                    FeedListItemContent(
                        item = item,
                        carouselPrefs = carouselPrefs,
                        selectedItemIds = selectedItemIds,
                        showCategoryFilterInline = showCategoryFilterInline,
                        onQuickFilterOptionClick = onQuickFilterOptionClick,
                        onFeedItemClick = onFeedItemClick,
                        onFeedItemLongClick = onFeedItemLongClick,
                        onFeedItemContinueReading = onFeedItemContinueReading,
                        onUpdatedContentItemClick = onUpdatedContentItemClick,
                        onUpdatedContentMoreClick = onUpdatedContentMoreClick,
                        onCaptureNavigationSnapshot = captureNavigationSnapshot,
                        onRetry = pagingItems?.let { it::retry } ?: {},
                    )
                }
            }
            pagingStatusItem?.let { statusItem ->
                item(
                    key = "feed_paging_status",
                    contentType = feedItemContentType(statusItem),
                ) {
                    FeedListItemContent(
                        item = statusItem,
                        carouselPrefs = carouselPrefs,
                        selectedItemIds = selectedItemIds,
                        showCategoryFilterInline = showCategoryFilterInline,
                        onQuickFilterOptionClick = onQuickFilterOptionClick,
                        onFeedItemClick = onFeedItemClick,
                        onFeedItemLongClick = onFeedItemLongClick,
                        onFeedItemContinueReading = onFeedItemContinueReading,
                        onUpdatedContentItemClick = onUpdatedContentItemClick,
                        onUpdatedContentMoreClick = onUpdatedContentMoreClick,
                        onCaptureNavigationSnapshot = captureNavigationSnapshot,
                        onRetry = pagingItems?.let { it::retry } ?: {},
                    )
                }
            }
        }
    }
}

private fun feedItemKey(item: ListModel): Any = when (item) {
    is QuickFilter -> "feed_filters"
    is FeedItem -> "feed_${item.id}"
    is UpdatedContentHeader -> "updates_header"
    is ListHeader -> "header_${item.hashCode()}"
    is LoadingState -> "loading"
    is EmptyState -> "empty"
    is ErrorState -> "error"
    else -> "${item::class.java.name}_${item.hashCode()}"
}

private fun feedItemContentType(item: ListModel): Any = when (item) {
    is QuickFilter -> "feed_filter"
    is FeedItem -> "feed_item"
    is UpdatedContentHeader -> "updated_carousel"
    is ListHeader -> "list_header"
    else -> "feed_other"
}

@Composable
private fun FeedListItemContent(
    item: ListModel,
    carouselPrefs: UpdatedContentCarouselPrefs,
    selectedItemIds: Set<Long>,
    showCategoryFilterInline: Boolean,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    onFeedItemClick: (FeedItem, Rect?) -> Unit,
    onFeedItemLongClick: (FeedItem) -> Unit,
    onFeedItemContinueReading: (FeedItem) -> Unit,
    onUpdatedContentItemClick: (UpdatedContentHeaderItem, Rect?) -> Unit,
    onUpdatedContentMoreClick: (UpdatedContentHeader) -> Unit,
    onCaptureNavigationSnapshot: (ListModel) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    when (item) {
        is QuickFilter -> if (showCategoryFilterInline) {
            org.skepsun.kototoro.list.ui.compose.QuickFilterSection(
                quickFilter = item,
                onQuickFilterOptionClick = onQuickFilterOptionClick,
            )
        }
        is FeedItem -> FeedItemCard(
            item = item,
            isSelected = item.id in selectedItemIds,
            onClick = { coverBounds ->
                onCaptureNavigationSnapshot(item)
                onFeedItemClick(item, coverBounds)
            },
            onLongClick = { onFeedItemLongClick(item) },
            onContinueReading = if (selectedItemIds.isEmpty()) {
                { onFeedItemContinueReading(item) }
            } else {
                null
            },
        )
        is UpdatedContentHeader -> UpdatedContentCarousel(
            header = item,
            prefs = carouselPrefs,
            onItemClick = { contentItem, coverBounds ->
                onCaptureNavigationSnapshot(item)
                onUpdatedContentItemClick(contentItem, coverBounds)
            },
            onMoreClick = { onUpdatedContentMoreClick(item) },
        )
        is ListHeader -> Text(
            text = item.getText(context)?.toString().orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LoadingState -> FeedLoadingState()
        is EmptyState -> FeedEmptyState(item)
        is ErrorState -> FeedErrorState(item = item, onRetry = onRetry)
    }
}

@Composable
private fun FeedErrorState(
    item: ErrorState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(if (item.icon != 0) item.icon else R.drawable.ic_error_large),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = item.exception.localizedMessage ?: stringResource(R.string.error_occurred),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.canRetry) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(stringResource(item.buttonText.takeIf { it != 0 } ?: R.string.retry))
            }
        }
    }
}

@Composable
private fun FeedLoadingState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        KototoroLoadingIndicator()
    }
}

@Composable
private fun FeedEmptyState(
    item: EmptyState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (item.icon != 0) {
            Icon(
                painter = painterResource(item.icon),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        val titleText = item.textPrimaryText?.toString()
        Text(
            text = titleText ?: stringResource(item.textPrimary),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val subtitleText = item.textSecondaryText?.toString()
        if (item.textSecondary != 0 || !subtitleText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitleText ?: stringResource(item.textSecondary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
