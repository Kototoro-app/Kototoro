package org.skepsun.kototoro.list.ui.compose

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.RetainedPagingSnapshotHost
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListModel

/**
 * Shared "retained paging snapshot" machinery used by the library / history /
 * updated / feed lists to keep the scroll position stable when the user returns
 * from a details page whose refresh invalidated the Room-backed list.
 *
 * Root cause it addresses: entering details and refreshing (auto or manual)
 * writes `manga` (and friends), which Room propagates to the list's PagingSource
 * invalidation. While the list is off-screen a fresh generation reloads, so a
 * plain `rememberSaveable(saver = LazyListState.Saver)` index restore points at
 * the wrong item / a not-yet-loaded window when the user comes back.
 *
 * Strategy (the design previously shipped by
 * `fix(favourites): stabilize paging position on detail return` /
 * `... restore original viewport on return ...` and removed by the paging perf
 * refactor):
 * 1. On navigate-away the caller captures [RetainedPagingSnapshot] into the
 *    [ContentListViewModel] (visible window + anchor item id + list mode +
 *    the raw layout index/offset).
 * 2. On return, while the live data has not yet (re)loaded the anchor, this
 *    renders the retained window (`displayedItems` = leading + snapshot) and
 *    seeds the scroll state with the snapshot's own raw index so the first
 *    frames show exactly the old viewport (no blank / wrong-window flash).
 * 3. Once the return transition settles, the paging refresh settles and the
 *    anchor item is present in the live data, the list scrolls to the anchor
 *    item's new position (`layoutOffset + liveAnchorIndex`) and the snapshot is
 *    released. If the anchor was genuinely removed from the dataset the
 *    snapshot is dropped without aligning.
 *
 * `feed`-style static lists pass `lazyPagingItems = null`; the same contract
 * applies but "refresh settled" means the live list contains the anchor.
 */
internal data class RetainedPagingSnapshotState(
    val gridState: LazyGridState,
    val listState: LazyListState,
    val detailedListState: LazyListState,
    /** Leading items plus the retained window while the snapshot is in use. */
    val displayedItems: List<ListModel>,
    /** Null while the retained window is rendered (paging stream paused). */
    val displayedPagingItems: LazyPagingItems<ListModel>?,
    val useRetainedPagingSnapshot: Boolean,
    /** True when the live paging refresh is running (drives the refresh spinner). */
    val pagingIsRefreshing: Boolean,
    /** The snapshot currently being rendered (non-null only while retained). */
    val currentRetainedSnapshot: ContentListViewModel.RetainedPagingSnapshot?,
    /**
     * Capture the current visible window before navigating to details.
     * [clickedItemId] is a stable list-model id used as the anchor fallback;
     * [layoutFirstVisibleIndex] is the raw index reported by the grid/list
     * state; [pagingAnchorIndex] is the index (within [loadedItems], paging
     * space) of the first visible item, so screens with section headers can
     * adjust for them.
     */
    val captureOnNavigate: (
        clickedItemId: Long,
        loadedItems: List<ListModel>,
        layoutFirstVisibleIndex: Int,
        firstVisibleScrollOffset: Int,
        mode: ListMode,
        pagingAnchorIndex: Int,
    ) -> Unit,
)

internal fun shouldUseRetainedPagingSnapshot(
    retentionEnabled: Boolean,
    hasPagingItems: Boolean,
    hasRetainedSnapshot: Boolean,
    returnTransitionSettled: Boolean,
    retainedAnchorPrefixIsReady: Boolean,
    pagingRefreshSettled: Boolean,
    retainedAnchorIsLoaded: Boolean,
): Boolean {
    val refreshedAnchorWasRemoved = returnTransitionSettled &&
        pagingRefreshSettled &&
        !retainedAnchorIsLoaded
    return retentionEnabled &&
        hasPagingItems &&
        hasRetainedSnapshot &&
        !refreshedAnchorWasRemoved &&
        (!retainedAnchorPrefixIsReady || !returnTransitionSettled)
}

private fun List<ListModel>.contentIndexOf(itemId: Long): Int {
    return indexOfFirst { model -> model is ContentListModel && model.id == itemId }
}

@Composable
internal fun rememberRetainedPagingSnapshotState(
    host: RetainedPagingSnapshotHost,
    retainEnabled: Boolean,
    leadingItems: List<ListModel>,
    lazyPagingItems: LazyPagingItems<ListModel>?,
    listMode: ListMode,
): RetainedPagingSnapshotState {
    val initialRetainedPagingSnapshot = remember(host, retainEnabled) {
        if (retainEnabled) host.peekRetainedPagingSnapshot() else null
    }
    var retainedPagingSnapshot by remember(host, retainEnabled) {
        mutableStateOf(initialRetainedPagingSnapshot)
    }

    val restoredViewportIndex = initialRetainedPagingSnapshot?.firstVisibleItemIndex ?: 0
    val restoredViewportOffset = initialRetainedPagingSnapshot?.firstVisibleItemScrollOffset ?: 0
    val restoreGridViewport = initialRetainedPagingSnapshot?.listMode == ListMode.GRID ||
        initialRetainedPagingSnapshot?.listMode == ListMode.COMPACT_GRID
    val restoreListViewport = initialRetainedPagingSnapshot?.listMode == ListMode.LIST
    val restoreDetailedListViewport = initialRetainedPagingSnapshot?.listMode == ListMode.DETAILED_LIST

    val gridState = if (initialRetainedPagingSnapshot != null) {
        key("retained_paging_grid", initialRetainedPagingSnapshot.generation) {
            rememberSaveable(saver = LazyGridState.Saver) {
                LazyGridState(
                    firstVisibleItemIndex = restoredViewportIndex.takeIf { restoreGridViewport } ?: 0,
                    firstVisibleItemScrollOffset = restoredViewportOffset.takeIf { restoreGridViewport } ?: 0,
                )
            }
        }
    } else {
        rememberSaveable(host, saver = LazyGridState.Saver) {
            LazyGridState()
        }
    }
    val listState = if (initialRetainedPagingSnapshot != null) {
        key("retained_paging_list", initialRetainedPagingSnapshot.generation) {
            rememberSaveable(saver = LazyListState.Saver) {
                LazyListState(
                    firstVisibleItemIndex = restoredViewportIndex.takeIf { restoreListViewport } ?: 0,
                    firstVisibleItemScrollOffset = restoredViewportOffset.takeIf { restoreListViewport } ?: 0,
                )
            }
        }
    } else {
        rememberSaveable(host, saver = LazyListState.Saver) {
            LazyListState()
        }
    }
    val detailedListState = if (initialRetainedPagingSnapshot != null) {
        key("retained_paging_detailed_list", initialRetainedPagingSnapshot.generation) {
            rememberSaveable(saver = LazyListState.Saver) {
                LazyListState(
                    firstVisibleItemIndex = restoredViewportIndex.takeIf { restoreDetailedListViewport } ?: 0,
                    firstVisibleItemScrollOffset = restoredViewportOffset.takeIf { restoreDetailedListViewport } ?: 0,
                )
            }
        }
    } else {
        rememberSaveable(host, saver = LazyListState.Saver) {
            LazyListState()
        }
    }

    // Only mark the return transition settled once the destination is fully
    // visible again; until then keep the retained window on screen.
    val navigationTransition = LocalNavAnimatedVisibilityScope.current?.transition
    var returnTransitionSettled by remember(host, initialRetainedPagingSnapshot?.generation) {
        mutableStateOf(initialRetainedPagingSnapshot == null)
    }
    LaunchedEffect(initialRetainedPagingSnapshot?.generation, navigationTransition) {
        val transition = navigationTransition
        if (initialRetainedPagingSnapshot == null || transition == null) {
            returnTransitionSettled = true
            return@LaunchedEffect
        }
        withFrameNanos { }
        snapshotFlow {
            !transition.isRunning &&
                transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible
        }.first { it }
        returnTransitionSettled = true
    }

    val isStaticList = lazyPagingItems == null
    // Static lists (feed) pass their whole window as `leadingItems`; paging lists
    // feed content exclusively through `lazyPagingItems`.
    val liveListItems = if (isStaticList) leadingItems else lazyPagingItems?.itemSnapshotList?.items.orEmpty()
    val retainedAnchorIndex = retainedPagingSnapshot?.let { retained ->
        retained.items.contentIndexOf(retained.anchorItemId)
    } ?: -1
    val liveAnchorIndex = retainedPagingSnapshot?.let { retained ->
        liveListItems.contentIndexOf(retained.anchorItemId)
    } ?: -1
    val retainedAnchorIsLoaded = liveAnchorIndex >= 0
    // Static lists (feed) load their whole window in one shot, so the prefix is
    // never "paging"; paging lists report end-of-pagination via prepend state.
    val pagingPrependExhausted = lazyPagingItems == null ||
        (lazyPagingItems.loadState.prepend as? LoadState.NotLoading)?.endOfPaginationReached == true
    val retainedAnchorPrefixIsReady = retainedAnchorIsLoaded &&
        (liveAnchorIndex >= retainedAnchorIndex || pagingPrependExhausted)
    val pagingRefreshSettled = if (lazyPagingItems != null) {
        lazyPagingItems.loadState.refresh is LoadState.NotLoading
    } else {
        // Static list (feed): "refresh settled" the moment there is live data.
        true
    }
    val useRetainedPagingSnapshot = shouldUseRetainedPagingSnapshot(
        retentionEnabled = retainEnabled,
        hasPagingItems = lazyPagingItems != null || isStaticList,
        hasRetainedSnapshot = retainedPagingSnapshot != null,
        returnTransitionSettled = returnTransitionSettled,
        retainedAnchorPrefixIsReady = retainedAnchorPrefixIsReady,
        pagingRefreshSettled = pagingRefreshSettled,
        retainedAnchorIsLoaded = retainedAnchorIsLoaded,
    )

    // Force the anchor page to load. Paging only prefetches around items that
    // are actually produced; while the retained window is shown the live paging
    // items are not consumed, so touch index 0 to warm the window.
    LaunchedEffect(
        useRetainedPagingSnapshot,
        returnTransitionSettled,
        retainedAnchorIndex,
        liveAnchorIndex,
        pagingPrependExhausted,
    ) {
        if (
            useRetainedPagingSnapshot &&
            returnTransitionSettled &&
            liveAnchorIndex in 0 until retainedAnchorIndex &&
            !pagingPrependExhausted
        ) {
            lazyPagingItems?.get(0)
        }
    }

    val displayedItems = remember(
        leadingItems,
        retainedPagingSnapshot,
        useRetainedPagingSnapshot,
        isStaticList,
    ) {
        if (useRetainedPagingSnapshot) {
            if (isStaticList) {
                retainedPagingSnapshot?.items.orEmpty()
            } else {
                leadingItems + retainedPagingSnapshot?.items.orEmpty()
            }
        } else {
            leadingItems
        }
    }
    val displayedPagingItems = if (useRetainedPagingSnapshot) null else lazyPagingItems

    // Re-align to the anchor by stable item id once the live data has loaded it.
    LaunchedEffect(useRetainedPagingSnapshot, liveListItems.size) {
        val snapshot = retainedPagingSnapshot ?: return@LaunchedEffect
        if (useRetainedPagingSnapshot) return@LaunchedEffect
        if (liveListItems.isEmpty()) return@LaunchedEffect
        val liveAnchorIndex = liveListItems.contentIndexOf(snapshot.anchorItemId)
        if (liveAnchorIndex < 0) {
            // The refreshed dataset removed the anchor; just stop retaining.
            host.clearRetainedPagingSnapshot(snapshot.generation)
            retainedPagingSnapshot = null
            return@LaunchedEffect
        }
        // Layout offset between the raw saved index and the saved anchor (leading
        // rows + section headers, e.g. favourites quick-filter row or the
        // history/updated header row). Restores the same visual offset the user
        // left, independent of how the list reordered.
        val layoutOffset = (snapshot.firstVisibleItemIndex -
            snapshot.items.contentIndexOf(snapshot.anchorItemId)).coerceAtLeast(0)
        val targetLayoutIndex = layoutOffset + liveAnchorIndex
        when (listMode) {
            ListMode.GRID, ListMode.COMPACT_GRID -> gridState.requestScrollToItem(
                index = targetLayoutIndex,
                scrollOffset = gridState.firstVisibleItemScrollOffset,
            )
            ListMode.LIST -> listState.requestScrollToItem(
                index = targetLayoutIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
            )
            ListMode.DETAILED_LIST -> detailedListState.requestScrollToItem(
                index = targetLayoutIndex,
                scrollOffset = detailedListState.firstVisibleItemScrollOffset,
            )
        }
        host.clearRetainedPagingSnapshot(snapshot.generation)
        retainedPagingSnapshot = null
    }

    val pagingIsRefreshing = lazyPagingItems?.loadState?.refresh is LoadState.Loading
    val captureOnNavigate: (
        clickedItemId: Long,
        loadedItems: List<ListModel>,
        layoutFirstVisibleIndex: Int,
        firstVisibleScrollOffset: Int,
        mode: ListMode,
        pagingAnchorIndex: Int,
    ) -> Unit = if (retainEnabled) {
        { clickedItemId, loadedItems, index, offset, mode, pagingAnchorIndex ->
            val firstVisiblePagingIndex = pagingAnchorIndex.coerceAtLeast(0)
            val anchorItemId = (loadedItems.getOrNull(firstVisiblePagingIndex) as? ContentListModel)?.id
                ?: clickedItemId
            host.retainPagingSnapshot(
                items = loadedItems,
                anchorItemId = anchorItemId,
                listMode = mode,
                firstVisibleItemIndex = index,
                firstVisibleItemScrollOffset = offset,
            )
        }
    } else {
        { _, _, _, _, _, _ -> }
    }

    return remember(
        gridState,
        listState,
        detailedListState,
        displayedItems,
        displayedPagingItems,
        useRetainedPagingSnapshot,
        pagingIsRefreshing,
        retainedPagingSnapshot,
        captureOnNavigate,
    ) {
        RetainedPagingSnapshotState(
            gridState = gridState,
            listState = listState,
            detailedListState = detailedListState,
            displayedItems = displayedItems,
            displayedPagingItems = displayedPagingItems,
            useRetainedPagingSnapshot = useRetainedPagingSnapshot,
            pagingIsRefreshing = pagingIsRefreshing,
            currentRetainedSnapshot = retainedPagingSnapshot,
            captureOnNavigate = captureOnNavigate,
        )
    }
}
