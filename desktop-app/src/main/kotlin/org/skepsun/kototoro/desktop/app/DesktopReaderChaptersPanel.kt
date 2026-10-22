package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.ui.chapters.*
import org.skepsun.kototoro.parsers.util.formatSimple
import org.skepsun.kototoro.reader.novel.compose.SharedNovelReaderSearchField
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChapterPanelHeader
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelTabBar
import org.skepsun.kototoro.reader.ui.compose.design.readerChapterPanelSubtitleText
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelSurfaceMode
import org.skepsun.kototoro.reader.ui.compose.rememberMangaReaderPanelColors

/** Platform data/actions only: the sheet, filters, list/grid and chapter cards come from the Android UI. */
@Composable
internal fun DesktopReaderChaptersPanel(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    val chapters = state.content?.chapters.orEmpty()
    val current = state.chapter
    val readingBranch = remember(chapters, current?.branch) {
        chaptersOfBranch(chapters, SourceChapter::branch, current?.branch)
    }
    val readingIndex = readingBranch.indexOfFirst { chapter ->
        current != null && DesktopDownloadStore.sameChapter(chapter, current)
    }
    var query by remember(state.content?.id) { mutableStateOf("") }
    var reversed by remember(state.content?.id) { mutableStateOf(false) }
    var grid by remember(state.content?.id) { mutableStateOf(false) }
    var branch by remember(state.content?.id) {
        mutableStateOf(if (current != null) current.branch else state.chapterBranch)
    }
    var locateRequest by remember { mutableIntStateOf(0) }
    val list = remember(chapters, branch, query, reversed) { desktopChapterList(chapters, branch, query, reversed) }
    val currentIndex = list.sections.indexOfFirst {
        it is ChapterSection.Item && current != null && DesktopDownloadStore.sameChapter(it.chapter, current)
    }.takeIf { it >= 0 }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val downloads by controller.session.downloadStore.records.collectAsState()
    val downloaded = remember(downloads, state.content?.id) {
        downloads.filter { it.contentId == state.content?.id && it.isComplete }.map { it.chapter }
    }
    val bookmarked = remember(state.bookmarks) { state.bookmarks.map { it.chapterId }.toSet() }
    ReaderPanelHost(
        colors = rememberMangaReaderPanelColors(state.readerSettings.background.toShared()),
        surfaceMode = ReaderPanelSurfaceMode.Opaque,
        onDismissRequest = onDismiss,
        openExpanded = true,
        modifier = Modifier.testTag("reader-panel"),
        header = {
            ReaderChapterPanelHeader(
                title = state.content?.title.orEmpty(),
                subtitle = readerChapterPanelSubtitleText(listOf(current?.let(::desktopChapterTitle),
                    readingIndex.takeIf { it >= 0 }?.let { "${it + 1}/${readingBranch.size}" })),
                actions = {
                    IconButton(
                        onClick = { query = ""; branch = current?.branch; locateRequest++ },
                        enabled = enabled && readingIndex >= 0,
                        modifier = Modifier.testTag("reader-chapter-locate"),
                    ) {
                        Icon(ReaderPanelIcons.CurrentChapter, AndroidStrings["novel_chapters_locate_current"])
                    }
                    TextButton(onDismiss, modifier = Modifier.testTag("reader-panel-close")) {
                        Text(AndroidStrings["close"])
                    }
                },
            )
            ReaderPanelTabBar(listOf(AndroidStrings["chapters"]), selectedIndex = 0, onSelected = {})
        },
    ) { dragModifier ->
        Column(Modifier.fillMaxSize()) {
            SharedNovelReaderSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = AndroidStrings["search_chapters"],
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                clearContentDescription = AndroidStrings["clear"],
                queryTag = "reader-chapter-query",
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(dragModifier),
                verticalAlignment = Alignment.CenterVertically) {
                Text(desktopText("reader.chaptersCount", list.count), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                IconButton({ reversed = !reversed }, enabled = enabled,
                    modifier = Modifier.testTag("reader-chapter-reverse")) {
                    Icon(ReaderPanelIcons.SortDesc, AndroidStrings["reverse_order"])
                }
                IconToggleButton(grid, { grid = it }, enabled = enabled,
                    modifier = Modifier.testTag("reader-chapter-grid")) {
                    Icon(ReaderPanelIcons.Grid, AndroidStrings["chapters_grid_view"])
                }
            }
            ChapterDirectoryLayout(
                items = list.sections,
                itemKey = { section -> when (section) {
                    is ChapterSection.Header -> "header:${section.volume}:${section.customName}"
                    is ChapterSection.Item -> "chapter:${section.chapter.id}:${section.chapter.url}"
                } },
                isSectionHeader = { it is ChapterSection.Header },
                isGridView = grid,
                gridScale = 1f,
                modifier = Modifier.weight(1f),
                isScrollEnabled = enabled,
                emptyMessage = AndroidStrings["no_chapters"],
                initialItemIndex = currentIndex,
                locateRequest = locateRequest,
                listState = listState,
                gridState = gridState,
                filters = {
                    ChapterBranchChips(list.options, list.selectedBranch,
                        title = { it ?: desktopText("details.chapterBranchDefault") },
                        onSelect = { branch = it }, enabled = enabled, modifier = dragModifier,
                        chipModifier = { Modifier.testTag("reader-chapter-branch:${it.name ?: "default"}") })
                },
                listScrollbar = {
                    VerticalScrollbar(rememberScrollbarAdapter(listState),
                        Modifier.align(Alignment.CenterEnd).padding(vertical = 16.dp))
                },
                gridScrollbar = {
                    VerticalScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd))
                },
            ) { _, section, isGrid ->
                when (section) {
                    is ChapterSection.Header -> ChapterSectionHeader(desktopChapterSectionTitle(section),
                        Modifier.testTag("reader-chapter-header:${desktopChapterSectionTitle(section)}"))
                    is ChapterSection.Item -> {
                        val chapter = section.chapter
                        val data = desktopChapterCardData(chapter, current, bookmarked, downloaded)
                        val onClick = {
                            if (enabled) {
                                if (current == null || !DesktopDownloadStore.sameChapter(chapter, current)) controller.read(chapter)
                                onDismiss()
                            }
                        }
                        val cardModifier = Modifier.testTag("reader-chapter:${chapter.id}")
                        DesktopChapterDirectoryCard(chapter, data, isGrid, enabled, isSelected = false,
                            onClick = onClick, modifier = cardModifier)
                    }
                }
            }
        }
    }
}

internal fun desktopChapterTitle(chapter: SourceChapter): String {
    chapter.title?.takeIf(String::isNotBlank)?.let { return it }
    val number = chapter.numberLabel() ?: return AndroidStrings["unnamed_chapter"]
    return if (chapter.volume > 0) AndroidStrings["chapter_volume_number"].format(chapter.volume.toString(), number)
    else AndroidStrings["chapter_number"].format(number)
}

/** The parser API's number label, with the same unknown-number policy as Android's chapter model. */
internal fun SourceChapter.numberLabel(): String? = number.takeIf { it > 0f }?.formatSimple()

internal fun desktopChapterSectionTitle(section: ChapterSection.Header): String = section.customName
    ?: if (section.volume > 0) AndroidStrings["volume_"].format(section.volume) else AndroidStrings["volume_unknown"]
