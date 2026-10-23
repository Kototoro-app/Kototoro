package org.skepsun.kototoro.details.ui.pager.chapters.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isLocal
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.skepsun.kototoro.core.ui.chapters.ChapterDirectoryLayout
import org.skepsun.kototoro.core.ui.chapters.ChapterFilterChips
import org.skepsun.kototoro.core.ui.chapters.ChapterSectionHeader
import org.skepsun.kototoro.core.ui.chapters.chapterBranchChipLabel
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneNestedScrollConnection
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.details.ui.model.chapterFastScrollLabelAt
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.CollapsibleListHeader
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel

internal fun ListModel.isChapterSectionHeader(): Boolean =
    this is ListHeader || this is CollapsibleListHeader

private const val ChapterFocusAttempts = 20

/** Android models and platform effects over the same directory layout used by Windows. */
@Composable
fun ChaptersScreen(
    items: List<ListModel>,
    isGridView: Boolean,
    isScrollEnabled: Boolean = true,
    requestInitialFocus: Boolean = true,
    detailsPaneState: DetailsPaneState? = null,
    gridScale: Float,
    selectedItemIds: Set<Long>,
    filterChips: List<ChipModel>,
    isLoading: Boolean,
    emptyMessageResId: Int?,
    initialChapterId: Long?,
    onItemClick: (ChapterListItem) -> Unit,
    onItemLongClick: (ChapterListItem) -> Unit,
    /** Downloads one chapter from its row; null hides the per-chapter download button. */
    onItemDownload: ((ChapterListItem) -> Unit)? = null,
    /**
     * Translates one chapter from its row; null hides the per-chapter translate button.
     *
     * The second argument is true when the chapter already has a translation and the user confirmed
     * translating it again: the job must then ignore the renders of the previous run.
     */
    onItemTranslate: ((ChapterListItem, Boolean) -> Unit)? = null,
    onHeaderClick: (CollapsibleListHeader) -> Unit,
    onFilterChipClick: (ChipModel) -> Unit,
    onSelectionActionClick: (Int) -> Unit,
    onClearSelection: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    // A translated chapter keeps its translate button; using it again asks before dropping the existing renders.
    val retranslationRequest = remember { mutableStateOf<ChapterListItem?>(null) }
    val requestTranslate: (ChapterListItem) -> Unit = { item ->
        if (item.isTranslated || item.isTranslationStale) {
            retranslationRequest.value = item
        } else {
            onItemTranslate?.invoke(item, false)
        }
    }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val initialIndex = remember(items, initialChapterId) {
        items.indexOfFirst { it is ChapterListItem && it.chapter.id == initialChapterId }.takeIf { it >= 0 }
    }
    val focusTargetIndex = initialIndex ?: items.indexOfFirst { it is ChapterListItem }
    val defaultFocusRequester = remember { FocusRequester() }
    val fastScrollLabelProvider: (Int) -> String = remember(items) {
        { index -> items.chapterFastScrollLabelAt(index).orEmpty() }
    }
    LaunchedEffect(isTvPresentation, requestInitialFocus, focusTargetIndex >= 0, isGridView, isScrollEnabled) {
        if (isTvPresentation && requestInitialFocus && isScrollEnabled && focusTargetIndex >= 0) {
            kotlinx.coroutines.yield()
            // The target row can arrive a few frames after the pane is measured; retry briefly so a
            // restored chapter keeps the remote focus instead of leaving it on the previous pane.
            repeat(ChapterFocusAttempts) {
                if (runCatching { defaultFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }
    val paneNestedScrollConnection = rememberDetailsPaneNestedScrollConnection(
        state = detailsPaneState,
        canChildScrollBackward = { if (isGridView) gridState.canScrollBackward else listState.canScrollBackward },
    )
    val paneNestedScrollModifier = remember(paneNestedScrollConnection) {
        if (paneNestedScrollConnection != null) Modifier.nestedScroll(paneNestedScrollConnection) else Modifier
    }
    ChapterDirectoryLayout(
        items = items,
        itemKey = { item ->
            when (item) {
                is ChapterListItem -> "chapter_${item.chapter.id}_${item.chapter.url}"
                is CollapsibleListHeader -> "header_${item.groupId}"
                is ListHeader -> "header_${item.getText(context)}"
                else -> "item_${item::class.java.simpleName}"
            }
        },
        isSectionHeader = { it.isChapterSectionHeader() },
        isGridView = isGridView,
        gridScale = gridScale,
        isScrollEnabled = isScrollEnabled,
        isLoading = isLoading,
        emptyMessage = emptyMessageResId?.takeIf { it != 0 }?.let { stringResource(it) },
        initialItemIndex = initialIndex,
        listState = listState,
        gridState = gridState,
        contentModifier = paneNestedScrollModifier,
        scrollableModifier = if (isTvPresentation) Modifier.focusGroup() else Modifier,
        filters = {
            ChapterFilterChips(
                options = filterChips,
                key = { chip -> (chip.data as? ListFilterOption.Branch)?.titleText ?: chip.title ?: chip.titleResId },
                label = { chip ->
                    chapterBranchChipLabel(
                        chip.title?.toString() ?: if (chip.titleResId != 0) context.getString(chip.titleResId) else "",
                        chip.counter,
                    )
                },
                selected = ChipModel::isChecked,
                onClick = onFilterChipClick,
                modifier = dragModifier.then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
                chipModifier = { Modifier.tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false) },
            )
        },
        listScrollbar = {
            VerticalScrollbar(listState, draggable = isScrollEnabled,
                contentPadding = PaddingValues(vertical = 16.dp), labelProvider = fastScrollLabelProvider)
        },
        gridScrollbar = {
            VerticalScrollbar(gridState, draggable = isScrollEnabled,
                contentPadding = PaddingValues(16.dp), labelProvider = fastScrollLabelProvider)
        },
    ) { index, item, isGrid ->
        when (item) {
            is ChapterListItem -> {
                val onClick = {
                    if (selectedItemIds.isNotEmpty()) hapticFeedback.performSelectionHapticFeedback()
                    onItemClick(item)
                }
                val focusRequester = defaultFocusRequester.takeIf { index == focusTargetIndex }
                if (isGrid) {
                    ChapterGridCard(item, selectedItemIds.contains(item.chapter.id), onClick,
                        onLongClick = { onItemLongClick(item) }, focusRequester = focusRequester)
                } else {
                    val isSelectionActive = selectedItemIds.isNotEmpty()
                    val canDownload = onItemDownload != null && !isSelectionActive &&
                        !item.isDownloaded && !item.chapter.source.isLocal
                    val canTranslate = onItemTranslate != null && !isSelectionActive
                    ChapterListCard(item, selectedItemIds.contains(item.chapter.id), onClick,
                        onLongClick = { onItemLongClick(item) }, focusRequester = focusRequester,
                        onDownload = if (canDownload) ({ onItemDownload?.invoke(item) }) else null,
                        onTranslate = if (canTranslate) ({ requestTranslate(item) }) else null)
                }
            }
            is CollapsibleListHeader -> CollapsibleHeaderUI(item, onClick = { onHeaderClick(item) })
            is ListHeader -> ChapterHeaderUI(text = item.getText(context) ?: "")
        }
    }
    val retranslationTarget = retranslationRequest.value
    if (retranslationTarget != null) {
        AlertDialog(
            onDismissRequest = { retranslationRequest.value = null },
            title = { Text(stringResource(R.string.translate_chapter_again)) },
            text = { Text(stringResource(R.string.translate_chapter_again_summary)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        retranslationRequest.value = null
                        onItemTranslate?.invoke(retranslationTarget, true)
                    },
                ) {
                    Text(stringResource(R.string.translate))
                }
            },
            dismissButton = {
                TextButton(onClick = { retranslationRequest.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun CollapsibleHeaderUI(
    header: CollapsibleListHeader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChapterHeaderUI(header.text, header.isCollapsible, header.isExpanded, onClick, modifier)
}

@Composable
private fun ChapterHeaderUI(
    text: CharSequence,
    isCollapsible: Boolean = false,
    isExpanded: Boolean = true,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) = ChapterSectionHeader(
    text = text.toString(),
    modifier = modifier.tvFocusable(shape = RoundedCornerShape(8.dp), enabled = isCollapsible, addFocusTarget = false),
    isCollapsible = isCollapsible,
    isExpanded = isExpanded,
    expandIcon = rememberVectorPainter(Icons.Default.ArrowDropDown),
    onClick = onClick,
)
