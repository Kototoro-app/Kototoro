package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll

/** Hoist one instance per shelf; grid/list positions survive display-mode and route changes independently. */
class ContentCollectionState(val grid: LazyGridState, val list: LazyListState)

@Composable
fun rememberContentCollectionState(): ContentCollectionState {
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    return remember(grid, list) { ContentCollectionState(grid, list) }
}

/** Android's indexed lazy grid. Paging access and card rendering remain supplied by each host. */
@Composable
fun ContentCollectionGrid(
    count: Int,
    key: (Int) -> Any,
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentType: (Int) -> Any? = { null },
    fullSpan: (Int) -> Boolean = { false },
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    canLoadMore: Boolean = false,
    visibleThreshold: Int = 1,
    onLoadMore: () -> Unit = {},
    itemContent: @Composable LazyGridItemScope.(Int) -> Unit,
) {
    CollectionLoadMoreEffect(state, canLoadMore, visibleThreshold, onLoadMore) {
        state.layoutInfo.let { it.totalItemsCount to (it.visibleItemsInfo.maxOfOrNull { row -> row.index } ?: -1) }
    }
    LazyVerticalGrid(columns, modifier.mouseDragScroll(state, Orientation.Vertical), state, contentPadding,
        verticalArrangement = verticalArrangement, horizontalArrangement = horizontalArrangement) {
        if (header != null) item(key = "collection:header", span = { GridItemSpan(maxLineSpan) },
            contentType = "collection:header") { header() }
        items(count, key = key, contentType = contentType,
            span = { GridItemSpan(if (fullSpan(it)) maxLineSpan else 1) }, itemContent = itemContent)
        if (footer != null) item(key = "collection:footer", span = { GridItemSpan(maxLineSpan) },
            contentType = "collection:footer") { footer() }
    }
}

/** Same header, stable-key and load-more contract for Android's compact/detailed rows and desktop shelves. */
@Composable
fun ContentCollectionColumn(
    count: Int,
    key: (Int) -> Any,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentType: (Int) -> Any? = { null },
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    canLoadMore: Boolean = false,
    visibleThreshold: Int = 1,
    onLoadMore: () -> Unit = {},
    itemContent: @Composable LazyItemScope.(Int) -> Unit,
) {
    CollectionLoadMoreEffect(state, canLoadMore, visibleThreshold, onLoadMore) {
        state.layoutInfo.let { it.totalItemsCount to (it.visibleItemsInfo.maxOfOrNull { row -> row.index } ?: -1) }
    }
    LazyColumn(modifier.mouseDragScroll(state, Orientation.Vertical), state, contentPadding,
        verticalArrangement = verticalArrangement) {
        if (header != null) item(key = "collection:header", contentType = "collection:header") { header() }
        items(count, key = key, contentType = contentType, itemContent = itemContent)
        if (footer != null) item(key = "collection:footer", contentType = "collection:footer") { footer() }
    }
}

/** Request once per observed item count; a retry/append cycle may explicitly disable and re-enable loading. */
@Composable
private fun CollectionLoadMoreEffect(
    state: Any,
    enabled: Boolean,
    threshold: Int,
    onLoadMore: () -> Unit,
    viewport: () -> Pair<Int, Int>,
) {
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, enabled, threshold) {
        if (!enabled) return@LaunchedEffect
        var lastRequestedCount = -1
        snapshotFlow {
            val (count, lastVisible) = viewport()
            if (count > 0 && lastVisible >= 0 && lastVisible >= count - threshold.coerceAtLeast(1)) count else -1
        }.distinctUntilChanged().collect { count ->
            if (count > 0 && count != lastRequestedCount) {
                lastRequestedCount = count
                latestLoadMore()
            }
        }
    }
}
