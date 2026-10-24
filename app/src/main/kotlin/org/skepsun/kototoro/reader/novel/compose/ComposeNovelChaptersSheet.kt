package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.prefs.rememberChapterRowActionPrefs
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableIntStateOf
import org.skepsun.kototoro.reader.ui.compose.ReaderChapterPanelMenuItem
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChapterPanelHeader
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelTabBar
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.forEInk
import org.skepsun.kototoro.reader.ui.compose.design.readerChapterPanelSubtitle
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.rememberReaderPanelSurfaceMode

internal sealed interface NovelChapterListItem {
    val key: String
    data class Header(val title: String, val occurrence: Int) : NovelChapterListItem {
        override val key = "header:$title:$occurrence"
    }
    data class Chapter(val chapter: ContentChapter, val originalIndex: Int) : NovelChapterListItem {
        override val key = "chapter:${chapter.id}:$originalIndex"
    }
}

@Immutable
internal data class NovelChapterSearchResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val excerpt: String,
    val matchStart: Int,
    /** Keyword occurrence ranges (indices into [excerpt]) used for highlighting. */
    val matchRanges: List<IntRange> = emptyList(),
)

@Composable
internal fun ComposeNovelChaptersSheet(
    chapters: List<ContentChapter>,
    currentIndex: Int,
    searchDocuments: List<NovelComposeChapterContent> = emptyList(),
    markings: List<NovelMarkingEntity> = emptyList(),
    bookmarks: List<Bookmark> = emptyList(),
    initialTab: NovelChaptersSheetTab = NovelChaptersSheetTab.CHAPTERS,
    themePreset: NovelReaderThemePreset = NovelReaderThemePreset.PAPER,
    onDismiss: () -> Unit,
    onChapterSelected: (Int) -> Unit,
    onSearchResultSelected: (NovelMarkingTarget) -> Unit = { target -> onChapterSelected(target.chapterIndex) },
    onJumpToMarking: (NovelMarkingEntity) -> Unit = {},
    onOpenBookmark: (Bookmark) -> Unit = {},
    onEditMarkingNote: (NovelMarkingEntity) -> Unit = {},
    onDeleteMarking: (NovelMarkingEntity) -> Unit = {},
    onDeleteBookmark: (Bookmark) -> Unit = {},
    bookTitle: String = "",
    eInkMode: Boolean = false,
    /** Chapters that already have a stored translation for the current settings. */
    translatedChapterIds: Set<Long> = emptySet(),
    /** Fraction translated for chapters whose translation is not complete yet. */
    translationProgress: Map<Long, Float> = emptyMap(),
    /** Translates one chapter from its row; null hides the in-row translate button. */
    onTranslateChapter: ((ContentChapter, Boolean) -> Unit)? = null,
    /** Chapters available offline already. */
    downloadedChapterIds: Set<Long> = emptySet(),
    /** Downloads one chapter from its row; null hides the in-row download button. */
    onDownloadChapter: ((ContentChapter) -> Unit)? = null,
) {
    val rowActions = rememberChapterRowActionPrefs()
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = initialTab.ordinal.coerceIn(0, NovelChaptersSheetTab.entries.lastIndex),
        pageCount = { NovelChaptersSheetTab.entries.size },
    )
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(initialTab) {
        val target = initialTab.ordinal.coerceIn(0, NovelChaptersSheetTab.entries.lastIndex)
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }
    var locateRequest by remember { mutableIntStateOf(0) }
    val panelColors = novelReaderPanelColors(novelReaderPalette(themePreset, isSystemInDarkTheme()))

    ReaderPanelHost(
        colors = if (eInkMode) panelColors.forEInk() else panelColors,
        surfaceMode = rememberReaderPanelSurfaceMode(eInkMode),
        onDismissRequest = onDismiss,
        header = {
            ReaderChapterPanelHeader(
                title = bookTitle,
                subtitle = readerChapterPanelSubtitle(
                    listOf(
                        chapters.getOrNull(currentIndex)?.title,
                        if (chapters.isNotEmpty()) "${currentIndex + 1}/${chapters.size}" else null,
                    ),
                ),
                actions = {
                    NovelChapterLocateButton(
                        description = stringResource(R.string.novel_chapters_locate_current),
                        enabled = chapters.isNotEmpty(),
                        onClick = {
                            coroutineScope.launch { pagerState.scrollToPage(NovelChaptersSheetTab.CHAPTERS.ordinal) }
                            locateRequest++
                        },
                    )
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more),
                            )
                        }
                        GlassDropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                        ) {
                            ReaderChapterPanelMenuItem(
                                text = stringResource(R.string.chapter_show_download_button),
                                selected = rowActions.showDownload,
                                onClick = {
                                    moreMenuExpanded = false
                                    rowActions.setShowDownload(!rowActions.showDownload)
                                },
                            )
                            ReaderChapterPanelMenuItem(
                                text = stringResource(R.string.chapter_show_translate_button),
                                selected = rowActions.showTranslate,
                                onClick = {
                                    moreMenuExpanded = false
                                    rowActions.setShowTranslate(!rowActions.showTranslate)
                                },
                            )
                        }
                    }
                },
            )
            ReaderPanelTabBar(
                labels = NovelChaptersSheetTab.entries.map { stringResource(it.labelRes) },
                selectedIndex = pagerState.currentPage,
                onSelected = { index -> coroutineScope.launch { pagerState.animateScrollToPage(index) } },
            )
        },
    ) { sheetDragModifier ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val contentWidth = if (maxWidth >= 720.dp) 680.dp else maxWidth
            HorizontalPager(
                state = pagerState,
                overscrollEffect = null,
                modifier = Modifier
                    .widthIn(max = contentWidth)
                    .fillMaxHeight()
                    .align(Alignment.TopCenter),
            ) { pageIndex ->
                when (NovelChaptersSheetTab.entries[pageIndex]) {
                    NovelChaptersSheetTab.CHAPTERS -> {
                        ComposeNovelChaptersContent(
                            chapters = chapters,
                            currentIndex = currentIndex,
                            onChapterSelected = onChapterSelected,
                            locateRequest = locateRequest,
                            dragModifier = sheetDragModifier,
                            modifier = Modifier.fillMaxSize(),
                            translatedChapterIds = translatedChapterIds,
                            translationProgress = translationProgress,
                            onTranslateChapter = onTranslateChapter?.takeIf { rowActions.showTranslate },
                            downloadedChapterIds = downloadedChapterIds,
                            onDownloadChapter = onDownloadChapter?.takeIf { rowActions.showDownload },
                        )
                    }
                    NovelChaptersSheetTab.NOTES -> {
                        ComposeNovelNotesContent(
                            bookmarks = bookmarks,
                            markings = markings,
                            chapters = chapters,
                            onDismiss = onDismiss,
                            onJumpToMarking = onJumpToMarking,
                            onOpenBookmark = onOpenBookmark,
                            onEditNote = onEditMarkingNote,
                            onDelete = onDeleteMarking,
                            onDeleteBookmark = onDeleteBookmark,
                            showTitle = false,
                            dragModifier = sheetDragModifier,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    NovelChaptersSheetTab.SEARCH -> {
                        ComposeNovelChapterSearchContent(
                            chapters = chapters,
                            documents = searchDocuments,
                            onChapterSelected = onChapterSelected,
                            onSearchResultSelected = onSearchResultSelected,
                            dragModifier = sheetDragModifier,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

private val NovelChaptersSheetTab.labelRes: Int
    get() = when (this) {
        NovelChaptersSheetTab.CHAPTERS -> R.string.chapters
        NovelChaptersSheetTab.NOTES -> R.string.notes
        NovelChaptersSheetTab.SEARCH -> R.string.novel_reader_chapter_search_tab
    }

@Composable
internal fun ComposeNovelChaptersContent(
    chapters: List<ContentChapter>,
    currentIndex: Int,
    onChapterSelected: (Int) -> Unit,
    locateRequest: Int = 0,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    translatedChapterIds: Set<Long> = emptySet(),
    translationProgress: Map<Long, Float> = emptyMap(),
    onTranslateChapter: ((ContentChapter, Boolean) -> Unit)? = null,
    downloadedChapterIds: Set<Long> = emptySet(),
    onDownloadChapter: ((ContentChapter) -> Unit)? = null,
) {
    val context = LocalContext.current
    val directory = remember(chapters, translatedChapterIds, translationProgress, downloadedChapterIds) {
        chapters.map { chapter ->
            chapter.directoryEntry().copy(
                isTranslated = chapter.id in translatedChapterIds,
                translationProgress = translationProgress[chapter.id],
                isDownloaded = chapter.source.isLocal || chapter.id in downloadedChapterIds,
            )
        }
    }
    // Reusing the translate button on a translated chapter replaces that translation, so ask first.
    val retranslationRequest = remember { mutableStateOf<ContentChapter?>(null) }
    val requestTranslate: (ContentChapter) -> Unit = { chapter ->
        if (chapter.id in translatedChapterIds) {
            retranslationRequest.value = chapter
        } else {
            onTranslateChapter?.invoke(chapter, false)
        }
    }
    val volumeTitle: (Int) -> String = remember(context) { { context.getString(R.string.volume_, it) } }
    NovelChapterDirectoryContent(
        chapters = directory,
        labels = NovelChapterDirectoryLabels(
            search = stringResource(R.string.search_chapters),
            clear = stringResource(R.string.clear),
            reverse = stringResource(R.string.reverse_order),
            unnamed = stringResource(R.string.unnamed_chapter),
        ),
        volumeTitle = volumeTitle,
        currentIndex = currentIndex,
        onChapterSelected = onChapterSelected,
        locateRequest = locateRequest,
        dragModifier = dragModifier,
        modifier = modifier,
        onChapterTranslate = if (onTranslateChapter != null) {
            { entry -> chapters.firstOrNull { it.id == entry.id }?.let(requestTranslate) }
        } else {
            null
        },
        translateContentDescription = stringResource(R.string.translate_chapter),
        onChapterDownload = if (onDownloadChapter != null) {
            { entry -> chapters.firstOrNull { it.id == entry.id }?.let(onDownloadChapter) }
        } else {
            null
        },
        downloadContentDescription = stringResource(R.string.download),
        downloadedContentDescription = stringResource(R.string.downloaded),
    )
    val target = retranslationRequest.value
    if (target != null) {
        AlertDialog(
            onDismissRequest = { retranslationRequest.value = null },
            title = { Text(stringResource(R.string.translate_chapter_again)) },
            text = { Text(stringResource(R.string.translate_chapter_again_summary)) },
            confirmButton = {
                TvTextButton(
                    onClick = {
                        retranslationRequest.value = null
                        onTranslateChapter?.invoke(target, true)
                    },
                ) {
                    Text(stringResource(R.string.translate))
                }
            },
            dismissButton = {
                TvTextButton(onClick = { retranslationRequest.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun ContentChapter.directoryEntry() = NovelChapterDirectoryEntry(
    id = id,
    title = title,
    volume = volume,
    groupTitle = branch?.takeIf(String::isNotBlank)
        ?: scanlator?.takeIf { it.isNotBlank() && source == LocalNovelSource }.orEmpty(),
    searchAliases = listOfNotNull(branch, scanlator),
)

@Composable
internal fun ComposeNovelChapterSearchContent(
    chapters: List<ContentChapter>,
    documents: List<NovelComposeChapterContent>,
    onChapterSelected: (Int) -> Unit,
    onSearchResultSelected: (NovelMarkingTarget) -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(chapters, documents, query) {
        searchNovelChapterContent(chapters, documents, query)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        NovelReaderSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.novel_reader_content_search_hint),
            clearContentDescription = stringResource(R.string.clear),
            modifier = dragModifier.fillMaxWidth(),
        )

        when {
            query.isBlank() -> {
                NovelChapterSearchEmptyState(
                    title = stringResource(R.string.novel_reader_content_search_empty_title),
                    message = stringResource(R.string.novel_reader_content_search_empty_hint),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            results.isEmpty() -> {
                NovelChapterSearchEmptyState(
                    title = stringResource(R.string.novel_reader_content_search_no_results),
                    message = stringResource(R.string.novel_reader_content_search_empty_hint),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.novel_reader_content_search_result_count, results.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragModifier.padding(horizontal = 4.dp),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(
                        items = results,
                        key = { result -> "search:${result.chapterIndex}:${result.matchStart}" },
                    ) { result ->
                        NovelChapterSearchResultCard(
                            result = result,
                            query = query,
                            onClick = {
                                val needle = query.trim()
                                val chapter = chapters.getOrNull(result.chapterIndex)
                                if (chapter != null && needle.isNotEmpty()) {
                                    onSearchResultSelected(
                                        NovelMarkingTarget(
                                            markingId = 0L,
                                            chapterId = chapter.id,
                                            chapterIndex = result.chapterIndex,
                                            startOffset = result.matchStart,
                                            endOffset = result.matchStart + needle.length,
                                            selectedText = needle,
                                        ),
                                    )
                                } else {
                                    onChapterSelected(result.chapterIndex)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelChapterSearchResultCard(
    result: NovelChapterSearchResult,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        background = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        fontWeight = FontWeight.SemiBold,
    )
    val needle = remember(query) { normalizeNovelSearchQuery(query) }
    val titleText = result.chapterTitle.ifBlank {
        stringResource(R.string.novel_reader_content_search_chapter_fallback, result.chapterIndex + 1)
    }
    val titleAnnotated = remember(titleText, needle, highlightStyle) {
        buildSearchHighlightedText(titleText, findSearchMatchRanges(titleText, needle), highlightStyle)
    }
    val excerptAnnotated = remember(result.excerpt, result.matchRanges, highlightStyle) {
        buildSearchHighlightedText(result.excerpt, result.matchRanges, highlightStyle)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = titleAnnotated,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (result.chapterIndex + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = excerptAnnotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NovelChapterSearchEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(horizontal = 28.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun NovelReaderSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearContentDescription: String? = null,
) = SharedNovelReaderSearchField(value, onValueChange, placeholder, modifier, clearContentDescription)

internal fun chapterListPositionForCurrent(
    items: List<NovelChapterListItem>,
    currentIndex: Int,
): Int = items.indexOfFirst {
    it is NovelChapterListItem.Chapter && it.originalIndex == currentIndex
}

internal fun buildChapterItems(
    chapters: List<ContentChapter>,
    reversed: Boolean,
    query: String,
    volumeTitle: (Int) -> String = { "Volume $it" },
): List<NovelChapterListItem> = buildNovelChapterDirectoryItems(
    chapters.map(ContentChapter::directoryEntry), reversed, query, volumeTitle,
).map { item ->
    when (item) {
        is NovelChapterDirectoryItem.Header -> NovelChapterListItem.Header(item.title, item.occurrence)
        is NovelChapterDirectoryItem.Chapter -> NovelChapterListItem.Chapter(
            chapters[item.originalIndex], item.originalIndex,
        )
    }
}

internal fun searchNovelChapterContent(
    chapters: List<ContentChapter>,
    documents: List<NovelComposeChapterContent>,
    query: String,
    maxResults: Int = 80,
    maxResultsPerChapter: Int = 4,
): List<NovelChapterSearchResult> {
    val needle = query.trim()
    if (needle.isEmpty() || maxResults <= 0 || maxResultsPerChapter <= 0) return emptyList()
    // Excerpts collapse whitespace runs, so scan the display form of the query for highlight ranges.
    val displayNeedle = needle.replace(novelSearchWhitespaceRegex, " ")
    val results = mutableListOf<NovelChapterSearchResult>()
    documents
        .asSequence()
        .filter { it.content.isNotBlank() }
        .distinctBy { it.chapterIndex }
        .sortedBy { it.chapterIndex }
        .forEach { document ->
            if (results.size >= maxResults) return@forEach
            val title = chapters
                .getOrNull(document.chapterIndex)
                ?.title
                ?.takeIf(String::isNotBlank)
                ?: document.chapterTitle
            var matchStart = document.content.indexOf(needle, ignoreCase = true)
            var chapterMatchCount = 0
            while (matchStart >= 0 && chapterMatchCount < maxResultsPerChapter && results.size < maxResults) {
                val matchEnd = matchStart + needle.length
                val excerptStart = (matchStart - 48).coerceAtLeast(0)
                val excerptEnd = (matchEnd + 108).coerceAtMost(document.content.length)
                val excerptBody = document.content
                    .substring(excerptStart, excerptEnd)
                    .replace(novelSearchWhitespaceRegex, " ")
                    .trim()
                val excerpt = buildString {
                    if (excerptStart > 0) append('…')
                    append(excerptBody)
                    if (excerptEnd < document.content.length) append('…')
                }
                results += NovelChapterSearchResult(
                    chapterIndex = document.chapterIndex,
                    chapterTitle = title,
                    excerpt = excerpt,
                    matchStart = matchStart,
                    matchRanges = findSearchMatchRanges(excerpt, displayNeedle),
                )
                chapterMatchCount++
                matchStart = document.content.indexOf(needle, startIndex = matchEnd, ignoreCase = true)
            }
        }
    return results
}

internal fun normalizeNovelSearchQuery(query: String): String =
    query.trim().replace(novelSearchWhitespaceRegex, " ")

internal fun findSearchMatchRanges(text: String, needle: String): List<IntRange> {
    if (needle.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var index = text.indexOf(needle, ignoreCase = true)
    while (index >= 0) {
        ranges += index until index + needle.length
        index = text.indexOf(needle, startIndex = index + needle.length, ignoreCase = true)
    }
    return ranges
}

internal fun buildSearchHighlightedText(
    text: String,
    ranges: List<IntRange>,
    style: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(0, text.length)
        if (end > start) {
            addStyle(style, start, end)
        }
    }
}

private val novelSearchWhitespaceRegex = Regex("\\s+")
