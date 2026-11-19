@file:OptIn(ExperimentalFoundationApi::class)

package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchStrategy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver

/**
 * Content grids mix full-span rows into the cells and change length under the scroll position. The cache-window prefetch that
 * Compose 1.13.0-rc01 turns on by default reads the span table out of range then (release crash: IndexOutOfBounds in
 * `LazyGridSpanLayoutProvider.getLineConfiguration` while dragging the suggestions page), so these grids keep the older
 * nested prefetch.
 */
fun contentGridState(firstVisibleItemIndex: Int = 0, firstVisibleItemScrollOffset: Int = 0) =
    LazyGridState(firstVisibleItemIndex, firstVisibleItemScrollOffset, LazyGridPrefetchStrategy())

@Composable
fun rememberContentGridState(): LazyGridState = rememberLazyGridState(prefetchStrategy = LazyGridPrefetchStrategy())

/** Saves the scroll position like [LazyGridState.Saver] but restores onto the same prefetch strategy. */
val ContentGridStateSaver = listSaver<LazyGridState, Int>(
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { contentGridState(it[0], it[1]) },
)
