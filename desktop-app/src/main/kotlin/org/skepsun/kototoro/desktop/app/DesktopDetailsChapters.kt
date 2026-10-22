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
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(desktopText("details.chapters", list.count), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
                    DesktopChapterDirectoryCard(
                        chapter, desktopChapterCardData(chapter, current, bookmarked, downloaded), grid, enabled,
                        onClick = { if (enabled) controller.read(chapter) },
                        modifier = Modifier.testTag("read-chapter:${chapter.id}"),
                        onDownload = if (DesktopReaderKind.of(state.content) == DesktopReaderKind.PAGES) {
                            { controller.downloadChapter(chapter) }
                        } else null,
                    )
                }
            }
        }
    }
}
