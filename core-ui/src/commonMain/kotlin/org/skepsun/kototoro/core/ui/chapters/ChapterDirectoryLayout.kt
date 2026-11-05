package org.skepsun.kototoro.core.ui.chapters

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll
import kotlin.math.roundToInt

/** Android's chapter directory layout; hosts adapt models, focus, nested scroll and scrollbar rendering. */
@Composable
fun <T> ChapterDirectoryLayout(
    items: List<T>,
    itemKey: (T) -> String,
    isSectionHeader: (T) -> Boolean,
    isGridView: Boolean,
    gridScale: Float,
    modifier: Modifier = Modifier,
    isScrollEnabled: Boolean = true,
    isLoading: Boolean = false,
    emptyMessage: String? = null,
    initialItemIndex: Int? = null,
    locateRequest: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState(),
    contentModifier: Modifier = Modifier,
    scrollableModifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    filters: @Composable () -> Unit = {},
    listScrollbar: @Composable BoxScope.() -> Unit = {},
    gridScrollbar: @Composable BoxScope.() -> Unit = {},
    itemContent: @Composable (index: Int, item: T, isGrid: Boolean) -> Unit,
) {
    val positionKeys = remember(items, itemKey) { items.map(itemKey) }
    val leadingCount = if (leadingContent == null) 0 else 1
    LaunchedEffect(initialItemIndex, positionKeys, isGridView, locateRequest, leadingCount) {
        val index = initialItemIndex?.takeIf { it in items.indices } ?: return@LaunchedEffect
        if (isGridView) gridState.scrollToItem(index + leadingCount) else listState.scrollToItem(index + leadingCount)
    }
    Column(modifier.fillMaxSize().testTag("chapter-directory-content")) {
        filters()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                leadingContent == null && items.isEmpty() && (isLoading || emptyMessage != null) ->
                    ChapterDirectoryEmptyState(isLoading, emptyMessage, Modifier.fillMaxSize())
                isGridView -> BoxWithConstraints(Modifier.fillMaxSize().then(contentModifier)) {
                    val columns = chapterGridColumns(maxWidth, gridScale)
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        userScrollEnabled = isScrollEnabled,
                        modifier = Modifier.fillMaxSize().then(scrollableModifier)
                            .mouseDragScroll(gridState, Orientation.Vertical, enabled = isScrollEnabled)
                            .testTag("chapter-directory-grid"),
                    ) {
                        if (leadingContent != null) {
                            item(key = "chapter-directory-leading", span = { GridItemSpan(maxLineSpan) }) {
                                leadingContent()
                            }
                        }
                        items(
                            count = items.size,
                            // Sources can repeat chapter ids and group names. Keep positions unambiguous on both hosts.
                            key = { index -> "${positionKeys[index]}_$index" },
                            span = { index -> GridItemSpan(if (isSectionHeader(items[index])) maxLineSpan else 1) },
                        ) { index -> itemContent(index, items[index], true) }
                        if (items.isEmpty() && (isLoading || emptyMessage != null)) {
                            item(key = "chapter-directory-empty", span = { GridItemSpan(maxLineSpan) }) {
                                ChapterDirectoryEmptyState(isLoading, emptyMessage,
                                    Modifier.fillMaxWidth().heightIn(min = 160.dp))
                            }
                        }
                    }
                    gridScrollbar()
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 16.dp),
                        userScrollEnabled = isScrollEnabled,
                        modifier = Modifier.fillMaxSize().then(scrollableModifier).then(contentModifier)
                            .mouseDragScroll(listState, Orientation.Vertical, enabled = isScrollEnabled)
                            .testTag("chapter-directory-list"),
                    ) {
                        if (leadingContent != null) {
                            item(key = "chapter-directory-leading") { leadingContent() }
                        }
                        items(count = items.size, key = { index -> "${positionKeys[index]}_$index" }) { index ->
                            itemContent(index, items[index], false)
                        }
                        if (items.isEmpty() && (isLoading || emptyMessage != null)) {
                            item(key = "chapter-directory-empty") {
                                ChapterDirectoryEmptyState(isLoading, emptyMessage,
                                    Modifier.fillMaxWidth().heightIn(min = 160.dp))
                            }
                        }
                    }
                    listScrollbar()
                }
            }
        }
    }
}

/** Empty results keep a stacked details header reachable instead of replacing the entire page. */
@Composable
private fun ChapterDirectoryEmptyState(isLoading: Boolean, message: String?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (isLoading) CircularProgressIndicator()
        else if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp))
    }
}

/**
 * Columns of the chapter grid for [width]: a card is about 80dp at 100%. The top of [ChapterGridSizeRange] reaches one
 * card per row on a phone, for chapters shown as their first page.
 */
fun chapterGridColumns(width: Dp, gridScale: Float): Int {
    val targetWidth = (gridScale * 80).dp.coerceIn(48.dp, 320.dp)
    return (width / targetWidth).roundToInt().coerceAtLeast(1)
}

/** Chapter grid size in percent; wider than the page-preview grid so page thumbnails can go one or two per row. */
val ChapterGridSizeRange: IntRange = 50..400
