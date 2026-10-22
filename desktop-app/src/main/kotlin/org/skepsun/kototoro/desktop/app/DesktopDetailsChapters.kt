package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.ui.chapters.*
import org.skepsun.kototoro.reader.novel.compose.SharedNovelReaderSearchField
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** Kept above the width-dependent panes so resizing does not discard search, order or the active lazy state. */
@Stable
internal class DesktopDetailsChapterState {
    var query by mutableStateOf("")
    var reversed by mutableStateOf(false)
    var grid by mutableStateOf(false)
    var locateRequest by mutableIntStateOf(0)
    var targetChapterId by mutableStateOf<Long?>(null)
    /** Android's batch mode; a mouse reaches it from the header toggle or by holding on a row. */
    var selecting by mutableStateOf(false)
    var selection by mutableStateOf(DesktopChapterSelection())
    val listState = LazyListState()
    val gridState = LazyGridState()

    /** Batch actions and plain clicks both end up here, so leaving the mode never leaves a selection behind. */
    fun leaveSelection() {
        selecting = false
        selection = DesktopChapterSelection()
    }
}

/** Windows data/actions over Android's shared directory; stacked details supply metadata as its leading item. */
@Composable
internal fun DesktopDetailsChapters(
    controller: DesktopController,
    state: DesktopAppState,
    ui: DesktopDetailsChapterState,
    modifier: Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val chapters = state.content?.chapters.orEmpty()
    val current = remember(chapters, state.detailsChapterId) { chapters.firstOrNull { it.id == state.detailsChapterId } }
    val list = remember(chapters, state.chapterBranch, ui.query, ui.reversed) {
        desktopChapterList(chapters, state.chapterBranch, ui.query, ui.reversed)
    }
    val targetIndex = list.sections.indexOfFirst {
        it is ChapterSection.Item && it.chapter.id == ui.targetChapterId
    }.takeIf { it >= 0 }
    val downloads by controller.session.downloadStore.records.collectAsState()
    val downloaded = remember(downloads, state.content?.id) {
        downloads.filter { it.contentId == state.content?.id && it.isComplete }.map { it.chapter }
    }
    val bookmarked = remember(state.bookmarks, state.content?.id) {
        state.bookmarks.filter { it.contentId == state.content?.id }.map { it.chapterId }.toSet()
    }
    val enabled = !state.busy
    // The menu works on the whole work even when the list is filtered to one branch, matching Android's dialog.
    val ordered = remember(chapters) {
        desktopChapterList(chapters, null, "", false).sections.mapNotNull { (it as? ChapterSection.Item)?.chapter }
    }
    val downloadMenu = remember(ordered, current, downloaded, state.chapterBranch) {
        val scopes = buildList {
            add(DesktopDownloadScope.WHOLE)
            add(DesktopDownloadScope.UNREAD)
            add(DesktopDownloadScope.BRANCH)
            add(DesktopDownloadScope.FIRST)
        }
        scopes.flatMap { scope ->
            val options = if (scope == DesktopDownloadScope.FIRST) {
                DesktopDownloadMenuOptions.map { DesktopDownloadOption(scope, count = it) }
            } else {
                listOf(DesktopDownloadOption(scope, branch = state.chapterBranch.takeIf { scope == DesktopDownloadScope.BRANCH }))
            }
            options.map { it to desktopDownloadTargets(ordered, current, downloaded, it) }
        }.filter { (_, targets) -> targets.isNotEmpty() }
    }
    val visible = remember(list.sections) {
        list.sections.mapNotNull { (it as? ChapterSection.Item)?.chapter }
    }
    val selectedChapters = ui.selection.resolve(visible)
    val selectedDownloaded = selectedChapters.count { chapter -> downloaded.any { DesktopDownloadStore.sameChapter(it, chapter) } }
    val pagesKind = DesktopReaderKind.of(state.content) == DesktopReaderKind.PAGES
    val controls: @Composable () -> Unit = {        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(desktopText("details.chapters", list.count), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({
                    if (ui.selecting) ui.leaveSelection() else ui.selecting = true
                }, enabled = enabled && list.count > 0, modifier = Modifier.testTag("details-chapter-select")) {
                    Text(if (ui.selecting) desktopText("details.cancelSelection") else desktopText("details.selectChapters"))
                }
                if (DesktopReaderKind.of(state.content) == DesktopReaderKind.PAGES) {
                    Box {
                        var open by remember { mutableStateOf(false) }
                        IconButton({ open = true }, enabled = enabled && downloadMenu.isNotEmpty(),
                            modifier = Modifier.testTag("details-download-menu")) {
                            Icon(ReaderPanelIcons.Download, desktopText("details.downloadChapters"))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            downloadMenu.forEach { (option, targets) ->
                                DropdownMenuItem({
                                    Text(desktopDownloadMenuLabel(option, targets.size, state.chapterBranch))
                                }, {
                                    open = false
                                    controller.downloadChapters(targets)
                                }, modifier = Modifier.testTag(desktopDownloadMenuTag(option)))
                            }
                        }
                    }
                }
                IconButton({
                    ui.query = ""; ui.targetChapterId = current?.id; ui.locateRequest++
                    controller.selectChapterBranch(current?.branch)
                }, enabled = enabled && current != null, modifier = Modifier.testTag("details-chapter-locate")) {
                    Icon(ReaderPanelIcons.CurrentChapter, AndroidStrings["novel_chapters_locate_current"])
                }
                IconButton({ ui.reversed = !ui.reversed }, enabled = enabled,
                    modifier = Modifier.testTag("details-chapter-reverse")) {
                    Icon(ReaderPanelIcons.SortDesc, AndroidStrings["reverse_order"])
                }
                IconToggleButton(ui.grid, { ui.grid = it }, enabled = enabled,
                    modifier = Modifier.testTag("details-chapter-grid")) {
                    Icon(ReaderPanelIcons.Grid, AndroidStrings["chapters_grid_view"])
                }
            }
            SharedNovelReaderSearchField(ui.query, { ui.query = it }, AndroidStrings["search_chapters"],
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                clearContentDescription = AndroidStrings["clear"], queryTag = "details-chapter-query")
            ChapterBranchChips(list.options, list.selectedBranch, title = { it ?: desktopText("details.chapterBranchDefault") },
                onSelect = controller::selectChapterBranch, enabled = enabled,
                chipModifier = { Modifier.testTag("chapter-branch:${it.name ?: "default"}") })
            if (ui.selecting) {
                DesktopChapterSelectionBar(
                    selection = ui.selection,
                    visible = visible,
                    downloadedCount = selectedDownloaded,
                    canDownload = pagesKind,
                    onSelectAll = { ui.selection = ui.selection.withAll(visible) },
                    onClear = { ui.selection = DesktopChapterSelection() },
                    onDownload = { controller.downloadChapters(selectedChapters) },
                    onMarkRead = { controller.markChaptersRead(selectedChapters) },
                    onDelete = { controller.deleteChapterDownloads(selectedChapters) },
                )
            }
        }
    }
    Box(modifier) {
        ChapterDirectoryLayout(
            items = list.sections,
            itemKey = { when (it) {
                is ChapterSection.Header -> "header:${it.volume}:${it.customName}"
                is ChapterSection.Item -> "chapter:${it.chapter.id}:${it.chapter.url}"
            } },
            isSectionHeader = { it is ChapterSection.Header },
            isGridView = ui.grid,
            gridScale = 1f,
            isScrollEnabled = enabled,
            emptyMessage = AndroidStrings["no_chapters"],
            initialItemIndex = targetIndex,
            locateRequest = ui.locateRequest,
            listState = ui.listState,
            gridState = ui.gridState,
            leadingContent = if (leadingContent == null) null else {
                { Column(Modifier.fillMaxWidth()) { leadingContent(); controls() } }
            },
            filters = { if (leadingContent == null) controls() },
            listScrollbar = {
                VerticalScrollbar(rememberScrollbarAdapter(ui.listState), Modifier.align(Alignment.CenterEnd))
            },
            gridScrollbar = {
                VerticalScrollbar(rememberScrollbarAdapter(ui.gridState), Modifier.align(Alignment.CenterEnd))
            },
        ) { _, section, grid ->
            when (section) {
                is ChapterSection.Header -> ChapterSectionHeader(desktopChapterSectionTitle(section),
                    Modifier.testTag("chapter-header:${desktopChapterSectionTitle(section)}"))
                is ChapterSection.Item -> {
                    val chapter = section.chapter
                    val isDownloaded = downloaded.any { DesktopDownloadStore.sameChapter(it, chapter) }
                    DesktopChapterDirectoryCard(
                        chapter, desktopChapterCardData(chapter, current, bookmarked, downloaded), grid, enabled,
                        isSelected = ui.selection.contains(chapter),
                        onClick = {
                            // In batch mode a click picks the row instead of leaving the list, like Android.
                            if (enabled) {
                                if (ui.selecting) ui.selection = ui.selection.toggle(chapter) else controller.read(chapter)
                            }
                        },
                        modifier = Modifier.testTag("read-chapter:${chapter.id}"),
                        onLongClick = {
                            if (enabled) {
                                ui.selecting = true
                                ui.selection = ui.selection.toggle(chapter)
                            }
                        },
                        showActions = !ui.selecting,
                        onDownload = if (DesktopReaderKind.of(state.content) == DesktopReaderKind.PAGES) {
                            { controller.downloadChapter(chapter) }
                        } else null,
                        // Android's long-press sheet; "read" stays usable on the current chapter so a badge can be reset.
                        onMarkRead = ({ controller.markChapterRead(chapter) }),
                        onMarkUnread = ({ controller.markChapterUnread(chapter) }),
                        onDeleteDownload = if (isDownloaded) ({ controller.deleteChapterDownload(chapter) }) else null,
                    )
                }
            }
        }
    }
}

/** Android's chapter selection bar: the same actions in the same order, as labels instead of icon-only buttons. */
@Composable
private fun DesktopChapterSelectionBar(
    selection: DesktopChapterSelection,
    visible: List<SourceChapter>,
    downloadedCount: Int,
    canDownload: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val hasSelection = !selection.isEmpty
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("details-selection-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            desktopText("details.selectionCount", selection.size),
            modifier = Modifier.weight(1f).testTag("details-selection-count"),
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(onSelectAll, enabled = selection.size < visible.size,
            modifier = Modifier.testTag("details-selection-all")) {
            Text(desktopText("details.selectAll"))
        }
        TextButton(onDownload, enabled = hasSelection && canDownload,
            modifier = Modifier.testTag("details-selection-download")) {
            Text(AndroidStrings["download"])
        }
        TextButton(onMarkRead, enabled = hasSelection,
            modifier = Modifier.testTag("details-selection-read")) {
            Text(desktopText("details.markRead"))
        }
        TextButton(onDelete, enabled = downloadedCount > 0,
            modifier = Modifier.testTag("details-selection-delete")) {
            Text(desktopText("details.deleteDownload"))
        }
        TextButton(onClear, enabled = hasSelection, modifier = Modifier.testTag("details-selection-clear")) {
            Text(desktopText("details.clearSelection"))
        }
    }
}
