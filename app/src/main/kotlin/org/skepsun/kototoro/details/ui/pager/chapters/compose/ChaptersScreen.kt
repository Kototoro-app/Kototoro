package org.skepsun.kototoro.details.ui.pager.chapters.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneNestedScrollConnection
import org.skepsun.kototoro.core.ui.widgets.ChipsView.ChipModel
import org.skepsun.kototoro.details.ui.model.chapterFastScrollLabelAt
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.list.ui.model.CollapsibleListHeader
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import kotlin.math.roundToInt

internal fun ListModel.isChapterSectionHeader(): Boolean =
    this is ListHeader || this is CollapsibleListHeader

@Composable
fun ChaptersScreen(
    items: List<ListModel>,
    isGridView: Boolean,
    isScrollEnabled: Boolean = true,
    detailsPaneState: DetailsPaneState? = null,
    gridScale: Float,
    selectedItemIds: Set<Long>,
    filterChips: List<ChipModel>,
    isLoading: Boolean,
    emptyMessageResId: Int?,
    initialChapterId: Long?,
    onItemClick: (ChapterListItem) -> Unit,
    onItemLongClick: (ChapterListItem) -> Unit,
    onHeaderClick: (CollapsibleListHeader) -> Unit,
    onFilterChipClick: (ChipModel) -> Unit,
    onSelectionActionClick: (Int) -> Unit,
    onClearSelection: () -> Unit,
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val itemPositionKeys = remember(items, context) {
        items.map { item ->
            when (item) {
                is ChapterListItem -> "chapter_${item.chapter.id}_${item.chapter.url}"
                is CollapsibleListHeader -> "header_${item.groupId}"
                is ListHeader -> "header_${item.getText(context)}"
                else -> "item_${item::class.java.simpleName}"
            }
        }
    }
    val fastScrollLabelProvider: (Int) -> String = remember(items) {
        { index ->
            items.chapterFastScrollLabelAt(index).orEmpty()
        }
    }
    LaunchedEffect(initialChapterId, itemPositionKeys, isGridView) {
        val chapterId = initialChapterId ?: return@LaunchedEffect
        val index = items.indexOfFirst { item ->
            item is ChapterListItem && item.chapter.id == chapterId
        }
        if (index == -1) {
            return@LaunchedEffect
        }
        if (isGridView) {
            gridState.scrollToItem(index)
        } else {
            listState.scrollToItem(index)
        }
    }
    val paneNestedScrollConnection = rememberDetailsPaneNestedScrollConnection(
        state = detailsPaneState,
        canChildScrollBackward = {
            if (isGridView) {
                gridState.canScrollBackward
            } else {
                listState.canScrollBackward
            }
        },
    )
    val paneNestedScrollModifier = remember(paneNestedScrollConnection) {
        if (paneNestedScrollConnection != null) {
            Modifier.nestedScroll(paneNestedScrollConnection)
        } else {
            Modifier
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (filterChips.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = filterChips,
                        key = { chip ->
                            val branch = chip.data as? org.skepsun.kototoro.list.domain.ListFilterOption.Branch
                            branch?.titleText ?: chip.title ?: chip.titleResId
                        },
                    ) { chip ->
                        FilterChip(
                            selected = chip.isChecked,
                            onClick = { onFilterChipClick(chip) },
                            label = {
                                Text(
                                    buildString {
                                        append(
                                            chip.title?.toString()
                                                ?: if (chip.titleResId != 0) stringResource(chip.titleResId) else "",
                                        )
                                        if (chip.counter > 0) {
                                            append(" · ")
                                            append(chip.counter)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                if (isLoading && items.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (items.isEmpty() && emptyMessageResId != null && emptyMessageResId != 0) {
                    Text(
                        text = stringResource(emptyMessageResId),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                } else if (isGridView) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(paneNestedScrollModifier),
                    ) {
                        val gridSpacing = 4.dp
                        val targetCardWidthDp = (gridScale * 80).dp.coerceIn(48.dp, 160.dp)
                        val gridSpanCount = remember(maxWidth, targetCardWidthDp) {
                            (maxWidth / targetCardWidthDp).roundToInt().coerceAtLeast(2)
                        }

                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(gridSpanCount),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(gridSpacing),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                            userScrollEnabled = isScrollEnabled,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                        items(
                            count = items.size,
                            key = { index ->
                                when (val item = items[index]) {
                                    is ChapterListItem -> "chapter_${item.chapter.id}_${item.chapter.url}_${index}"
                                    is CollapsibleListHeader -> "header_${item.groupId}_${index}"
                                    is ListHeader -> "header_${item.getText(context)}_${index}"
                                    else -> "item_${item::class.java.simpleName}_${index}"
                                }
                            },
                            span = { index ->
                                if (items[index].isChapterSectionHeader()) {
                                    GridItemSpan(maxLineSpan)
                                } else {
                                    GridItemSpan(1)
                                }
                            },
                        ) { index ->
                            when (val item = items[index]) {
                                is ChapterListItem -> {
                                    ChapterGridCard(
                                        item = item,
                                        isSelected = selectedItemIds.contains(item.chapter.id),
                                        onClick = {
                                            if (selectedItemIds.isNotEmpty()) {
                                                hapticFeedback.performSelectionHapticFeedback()
                                            }
                                            onItemClick(item)
                                        },
                                        onLongClick = { onItemLongClick(item) },
                                    )
                                }

                                is CollapsibleListHeader -> {
                                    CollapsibleHeaderUI(header = item, onClick = { onHeaderClick(item) })
                                }

                                is ListHeader -> {
                                    ChapterHeaderUI(text = item.getText(context) ?: "")
                                }
                            }
                        }
                        }
                        VerticalScrollbar(
                            state = gridState,
                            draggable = isScrollEnabled,
                            labelProvider = fastScrollLabelProvider,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 16.dp),
                        userScrollEnabled = isScrollEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(paneNestedScrollModifier),
                    ) {
                        items(
                            count = items.size,
                            key = { index ->
                                when (val item = items[index]) {
                                    is ChapterListItem -> "chapter_${item.chapter.id}_${item.chapter.url}_${index}"
                                    is CollapsibleListHeader -> "header_${item.groupId}_${index}"
                                    is ListHeader -> "header_${item.getText(context)}_${index}"
                                    else -> "item_${item::class.java.simpleName}_${index}"
                                }
                            },
                        ) { index ->
                            when (val item = items[index]) {
                                is ChapterListItem -> {
                                    ChapterListCard(
                                        item = item,
                                        isSelected = selectedItemIds.contains(item.chapter.id),
                                        onClick = {
                                            if (selectedItemIds.isNotEmpty()) {
                                                hapticFeedback.performSelectionHapticFeedback()
                                            }
                                            onItemClick(item)
                                        },
                                        onLongClick = { onItemLongClick(item) },
                                    )
                                }

                                is CollapsibleListHeader -> {
                                    CollapsibleHeaderUI(header = item, onClick = { onHeaderClick(item) })
                                }

                                is ListHeader -> {
                                    ChapterHeaderUI(text = item.getText(context) ?: "")
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        state = listState,
                        draggable = isScrollEnabled,
                        endInset = 16.dp,
                        labelProvider = fastScrollLabelProvider,
                    )
                }
            }
        }
    }
}

@Composable
fun CollapsibleHeaderUI(header: CollapsibleListHeader, onClick: () -> Unit) {
    ChapterHeaderUI(
        text = header.text,
        isCollapsible = header.isCollapsible,
        isExpanded = header.isExpanded,
        onClick = onClick,
    )
}

@Composable
private fun ChapterHeaderUI(
    text: CharSequence,
    isCollapsible: Boolean = false,
    isExpanded: Boolean = true,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isCollapsible, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isCollapsible) {
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = if (!isExpanded) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (!isExpanded) -90f else 0f),
            )
        }
    }
}
