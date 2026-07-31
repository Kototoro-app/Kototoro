package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.video.ui.PlayerChapterGroup

internal data class VideoChapterDialogState(
    val title: String,
    val groups: List<PlayerChapterGroup>,
    val currentChapterId: Long?,
    val initialPage: Int,
    val initialGridView: Boolean,
    val ungroupedTitle: String,
    val anchorBounds: IntRect = IntRect.Zero,
)

@Composable
internal fun VideoChapterDialog(
    state: VideoChapterDialogState,
    onDismissRequest: () -> Unit,
    onChapterSelected: (ContentChapter) -> Unit,
    onGridViewChanged: (Boolean) -> Unit,
) {
    if (state.groups.isEmpty()) return

    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val marginPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val panelHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.72f).dp
    val initialPage = state.initialPage.coerceIn(0, state.groups.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = state.groups::size,
    )
    val coroutineScope = rememberCoroutineScope()
    var isGridView by rememberSaveable(state.initialGridView) {
        mutableStateOf(state.initialGridView)
    }

    Popup(
        popupPositionProvider = PlayerMenuPositionProvider(
            targetBounds = state.anchorBounds,
            placement = PlayerMenuPlacement.BelowAnchor,
            gapPx = gapPx,
            marginPx = marginPx,
        ),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = true),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 380.dp)
                .height(panelHeight),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.86f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                    IconButton(
                        onClick = {
                            isGridView = !isGridView
                            onGridViewChanged(isGridView)
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (isGridView) {
                                Icons.AutoMirrored.Filled.ViewList
                            } else {
                                Icons.Filled.GridView
                            },
                            contentDescription = stringResource(if (isGridView) R.string.list else R.string.grid),
                            tint = Color.White,
                        )
                    }
                }
                if (state.groups.size > 1) {
                    CompactGroupTabs(
                        groups = state.groups,
                        selectedIndex = pagerState.currentPage,
                        ungroupedTitle = state.ungroupedTitle,
                        onSelect = { index ->
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    key = { page -> "$page:${state.groups[page].name.orEmpty()}" },
                ) { page ->
                    val group = state.groups[page]
                    val currentChapterIndex = group.chapters
                        .indexOfFirst { it.id == state.currentChapterId }
                        .coerceAtLeast(0)
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentChapterIndex)
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentChapterIndex)

                    LaunchedEffect(isGridView) {
                        if (isGridView) {
                            gridState.scrollToItem(listState.firstVisibleItemIndex)
                        } else {
                            listState.scrollToItem(gridState.firstVisibleItemIndex)
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 64.dp),
                                state = gridState,
                                contentPadding = PaddingValues(
                                    start = 10.dp,
                                    top = 8.dp,
                                    end = 36.dp,
                                    bottom = 8.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                gridItemsIndexed(
                                    items = group.chapters,
                                    key = { _, chapter -> chapter.id },
                                ) { index, chapter ->
                                    ChapterGridItem(
                                        chapter = chapter,
                                        index = index,
                                        checked = chapter.id == state.currentChapterId,
                                        onClick = { onChapterSelected(chapter) },
                                    )
                                }
                            }
                            VerticalScrollbar(
                                state = gridState,
                                color = Color.White.copy(alpha = 0.90f),
                                trackColor = Color.White.copy(alpha = 0.18f),
                                alwaysVisible = true,
                                endInset = 4.dp,
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(end = 32.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                itemsIndexed(
                                    items = group.chapters,
                                    key = { _, chapter -> chapter.id },
                                ) { index, chapter ->
                                    ChapterRow(
                                        chapter = chapter,
                                        index = index,
                                        checked = chapter.id == state.currentChapterId,
                                        onClick = { onChapterSelected(chapter) },
                                    )
                                }
                            }
                            VerticalScrollbar(
                                state = listState,
                                color = Color.White.copy(alpha = 0.90f),
                                trackColor = Color.White.copy(alpha = 0.18f),
                                alwaysVisible = true,
                                endInset = 4.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGroupTabs(
    groups: List<PlayerChapterGroup>,
    selectedIndex: Int,
    ungroupedTitle: String,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        itemsIndexed(
            items = groups,
            key = { index, group -> "$index:${group.name.orEmpty()}" },
        ) { index, group ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 36.dp, max = 112.dp)
                    .background(
                        color = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 9.dp),
            ) {
                Text(
                    text = group.name ?: ungroupedTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ContentChapter,
    index: Int,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = (index + 1).toString(),
            color = Color.White,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = chapter.title?.takeIf(String::isNotBlank) ?: chapter.url,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ChapterGridItem(
    chapter: ContentChapter,
    index: Int,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color.White.copy(alpha = if (checked) 0.18f else 0.08f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (checked) 0.42f else 0.12f)),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                text = chapter.title?.takeIf(String::isNotBlank) ?: chapter.url,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            )
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp),
                )
            }
        }
    }
}
