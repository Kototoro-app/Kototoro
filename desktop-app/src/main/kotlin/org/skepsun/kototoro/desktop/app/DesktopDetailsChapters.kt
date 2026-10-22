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
    val listState = LazyListState()
    val gridState = LazyGridState()
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
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(desktopText("details.chapters", list.count), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
                        onClick = { if (enabled) controller.read(chapter) },
                        modifier = Modifier.testTag("read-chapter:${chapter.id}"),
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
