package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.buttons.OutlinedButton
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeActions
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeLayout

private enum class NovelPanel { CHAPTERS, SETTINGS, BOOKMARKS }

/** Scrolling text reader for novel chapters: one block per paragraph, position kept as a block index. */
@Composable
internal fun DesktopNovelReader(controller: DesktopController, state: DesktopAppState, closing: Boolean,
    fullscreen: Boolean = false, onToggleFullscreen: (() -> Unit)? = null) {
    val original = state.novel ?: return
    val bookId = state.content?.id
    val translationSettings by controller.novelTranslation.settings.collectAsState()
    val translationRevision by controller.novelTranslation.revision.collectAsState()
    val paragraphs = remember(original.blocks) { desktopTranslationParagraphs(original.blocks) }
    var translation by remember(bookId, original.chapter.id, translationSettings.target) {
        mutableStateOf(DesktopChapterTranslation())
    }
    LaunchedEffect(bookId, original.chapter.id, translationSettings.target, translationRevision) {
        translation = if (bookId == null) DesktopChapterTranslation() else withContext(Dispatchers.IO) {
            runCatching { controller.novelTranslation.chapter(bookId, original.chapter.id, translationSettings.target) }
                .getOrDefault(DesktopChapterTranslation())
        }
    }
    var showTranslation by remember(bookId) { mutableStateOf(true) }
    var bilingual by remember(bookId) { mutableStateOf(false) }
    val novel = if (showTranslation && (translation.machine.isNotEmpty() || translation.edits.isNotEmpty())) {
        val displayed = remember(original.blocks, paragraphs, translation, bilingual) {
            desktopTranslatedBlocks(original.blocks, paragraphs, translation, bilingual)
        }
        original.copy(blocks = displayed)
    } else {
        original
    }
    val settings = state.novelSettings
    val colors = settings.theme
    val enabled = !state.busy && !closing
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<NovelPanel?>(null) }
    val hasPrevious = state.adjacentChapter(false) != null
    val hasNext = state.adjacentChapter(true) != null
    val paged = settings.paged
    // One pager per chapter: pages follow the measured window, while the anchor survives a mode switch.
    val pager = remember(novel.chapter.id) { DesktopNovelPagerState(novel.startBlock) }
    val chapterBreak = state.readerSettings.chapterBreak
    // Paged: the break is its own page after the last one; it never moves the page, the block or the progress.
    var breakOpen by remember(novel.chapter.id) { mutableStateOf(false) }
    fun flipPage(forward: Boolean) {
        if (!enabled) return
        if (chapterBreak && breakOpen) {
            // The break page sits between the last page and the next chapter: back closes it, forward reads on.
            if (!forward) { breakOpen = false; return }
        } else {
            val moved = if (forward) pager.next() else pager.previous()
            if (moved) return
            if (chapterBreak && forward) { breakOpen = true; return }
        }
        when {
            forward && hasNext -> controller.changeNovelChapter(true)
            !forward && hasPrevious -> controller.changeNovelChapter(false)
        }
    }
    fun back() {
        if (fullscreen) onToggleFullscreen?.invoke()
        controller.backToDetails()
    }
    // Position first, then reporting: the first emission must not overwrite a restored position with block 0.
    LaunchedEffect(novel.chapter.id, novel.navigation) {
        if (!paged) list.scrollToItem(novel.startBlock)
        focus.requestFocus()
    }
    // Switching modes continues at the paragraph being read: paged mode opens the page that holds it, and scrolling
    // resumes at the page's first paragraph.
    LaunchedEffect(paged) {
        if (paged) pager.jumpToBlock(novel.firstVisible)
        else list.scrollToItem(pager.anchorBlock.coerceIn(0, novel.blocks.lastIndex.coerceAtLeast(0)))
        focus.requestFocus()
    }
    // Each mode reports its own first/last block; switching modes restarts the reporter, not the position.
    LaunchedEffect(novel.chapter.id, paged) {
        if (paged) return@LaunchedEffect
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index < novel.blocks.size }
            // Items behind the fixed top padding are composed too; they are not the saved reading anchor.
            list.firstVisibleItemIndex.coerceAtMost(novel.blocks.lastIndex.coerceAtLeast(0)) to
                (visible.lastOrNull()?.index ?: 0)
        }.collect { (first, last) -> controller.novelProgress(novel.chapter.id, first, last.coerceAtLeast(first)) }
    }
    LaunchedEffect(novel.chapter.id, paged, pager.page, pager.pages) {
        if (!paged) return@LaunchedEffect
        pager.range?.let { range -> controller.novelProgress(novel.chapter.id, range.first, range.last) }
    }
    CompositionLocalProvider(LocalContentColor provides Color(colors.palette.text)) {
        Box(Modifier.fillMaxSize().background(Color(colors.palette.background)).testTag("novel-reader")
            .focusRequester(focus).focusable().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                    return@onPreviewKeyEvent false
                }
                when {
                    event.key == Key.Escape && panel != null -> { panel = null; focus.requestFocus(); true }
                    event.key == Key.Escape -> { back(); true }
                    panel != null -> false
                    event.key == Key.B -> { if (enabled) controller.toggleNovelBookmark(); true }
                    event.key == Key.H -> { controlsVisible = !controlsVisible; true }
                    event.key == Key.F11 && onToggleFullscreen != null -> { onToggleFullscreen(); true }
                    event.key == Key.PageDown || event.key == Key.Spacebar && !event.isShiftPressed -> {
                        if (paged) flipPage(true) else scope.launch { list.animateScrollBy(list.layoutInfo.viewportSize.height * .9f) }
                        true
                    }
                    event.key == Key.PageUp || event.key == Key.Spacebar -> {
                        if (paged) flipPage(false) else scope.launch { list.animateScrollBy(-list.layoutInfo.viewportSize.height * .9f) }
                        true
                    }
                    event.key == Key.MoveHome -> {
                        breakOpen = false
                        if (paged) pager.jumpToBlock(0) else scope.launch { list.scrollToItem(0) }
                        true
                    }
                    event.key == Key.MoveEnd -> {
                        breakOpen = false
                        if (paged) pager.jumpToBlock(novel.blocks.lastIndex) else scope.launch { list.scrollToItem(novel.blocks.lastIndex) }
                        true
                    }
                    else -> false
                }
            }) {
            SelectionContainer(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                    focus.requestFocus()
                })
            }) {
                if (paged) {
                    DesktopNovelPagedBody(controller, novel, settings, pager, onFlip = ::flipPage,
                        modifier = Modifier.fillMaxSize())
                } else {
                    DesktopNovelScrollBody(controller, novel, settings, list, enabled, hasPrevious, hasNext, colors,
                        breakCard = if (chapterBreak) {
                            { modifier -> DesktopNovelBreakCard(state, novel, modifier) }
                        } else {
                            null
                        })
                }
            }
            if (paged && chapterBreak && breakOpen) {
                DesktopNovelBreakCard(state, novel,
                    Modifier.fillMaxSize().background(Color(colors.palette.background)).testTag("novel-chapter-break"))
            }
            DesktopNovelReaderChrome(
                novel = novel,
                theme = colors,
                title = state.content?.title.orEmpty(),
                controlsVisible = controlsVisible,
                panelVisible = panel != null,
                previousEnabled = enabled && hasPrevious,
                nextEnabled = enabled && hasNext,
                bookmarked = if (paged) {
                    val saved = novel.bookmarkBlocks.values
                    pager.range?.any { it in saved } == true
                } else {
                    novel.firstVisible in novel.bookmarkBlocks.values
                },
                onToggleBookmark = { if (enabled) controller.toggleNovelBookmark() },
                onBookmarks = { panel = NovelPanel.BOOKMARKS },
                actions = NovelReaderChromeActions(
                    onBack = ::back,
                    onChapters = { panel = NovelPanel.CHAPTERS },
                    onOptions = { panel = NovelPanel.SETTINGS },
                onProgressSelected = { position ->
                    // Paged mode shows the slider in pages; the pager works in paragraphs, so the page decides.
                    val block = if (paged) pager.pages.getOrNull(position)?.first ?: position else position
                    if (enabled && block in novel.blocks.indices) {
                        breakOpen = false
                        if (paged) pager.jumpToBlock(block) else scope.launch { list.scrollToItem(block) }
                        focus.requestFocus()
                    }
                },
                    onPreviousChapter = { if (enabled) controller.changeNovelChapter(false) },
                    onNextChapter = { if (enabled) controller.changeNovelChapter(true) },
                ),
                onShowControls = { controlsVisible = true; focus.requestFocus() },
                pageStatus = if (paged && !pager.isEmpty) pager.page to pager.pages.size else null,
            )
            panel?.let { selected ->
                val close: () -> Unit = { panel = null; focus.requestFocus() }
                when (selected) {
                    NovelPanel.CHAPTERS -> DesktopNovelChaptersPanel(controller, state, enabled, close)
                    NovelPanel.BOOKMARKS -> DesktopNovelBookmarksPanel(controller, state, enabled, close)
                    NovelPanel.SETTINGS -> DesktopNovelOptionsPanel(
                        controller, settings, fullscreen, onToggleFullscreen, close,
                        translationOptions = state.content?.let { content ->
                            {
                                DesktopNovelTranslationOptions(controller, content, original, translation, paragraphs,
                                    showTranslation, { showTranslation = it }, bilingual, { bilingual = it })
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNovelScrollBody(
    controller: DesktopController,
    novel: DesktopNovel,
    settings: DesktopNovelSettings,
    list: LazyListState,
    enabled: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    colors: DesktopNovelTheme,
    breakCard: (@Composable (Modifier) -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize().testTag("novel-list"), state = list,
        contentPadding = PaddingValues(
            top = NovelReaderChromeLayout.TopHeight,
            bottom = NovelReaderChromeLayout.BottomHeight,
        )) {
        itemsIndexed(novel.blocks) { index, block ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = settings.width.dp).fillMaxWidth().testTag("novel-block:$index")) {
                    NovelBlockView(controller, block, settings)
                }
            }
        }
        if (breakCard != null) item(key = "chapter-break") {
            // A low band marks where the chapter ends; the chapter buttons below stay as they are.
            breakCard(Modifier.fillParentMaxHeight(BREAK_BAND_FRACTION).testTag("novel-chapter-break"))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center) {
                OutlinedButton({ controller.changeNovelChapter(false) }, enabled = enabled && hasPrevious,
                    colors = themedOutline(colors),
                    modifier = Modifier.testTag("novel-previous-chapter")) {
                    Text(desktopText("novel.previousChapter"))
                }
                Spacer(Modifier.width(16.dp))
                Button({ controller.changeNovelChapter(true) }, enabled = enabled && hasNext,
                    modifier = Modifier.testTag("novel-next-chapter")) {
                    Text(desktopText("novel.nextChapter"))
                }
            }
        }
    }
}

@Composable
internal fun NovelBlockView(
    controller: DesktopController,
    block: NovelBlock,
    settings: DesktopNovelSettings,
    styles: DesktopNovelTextStyles = settings.desktopNovelStyles(),
    maxImageHeight: Dp? = null,
) {
    val colors = settings.theme
    when (block) {
        is NovelBlock.Paragraph -> Text(block.annotatedText(), style = styles.body,
            modifier = Modifier.padding(bottom = (settings.fontSize * .7f).dp))
        is NovelBlock.Heading -> Text(block.text, style = styles.heading(block.level),
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
        NovelBlock.Rule -> Divider(Modifier.padding(vertical = 16.dp), color = Color(colors.palette.secondaryText).copy(alpha = .4f))
        is NovelBlock.Image -> NovelInlineImage(controller, block.url, Color(colors.palette.secondaryText),
            maxImageHeight?.let(::desktopNovelImageHeight))
    }
}

@Composable
private fun NovelInlineImage(controller: DesktopController, url: String, muted: Color, maxHeight: Dp? = null) {
    var failure by remember(url) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = null
        var unpublished: Bitmap? = null
        try {
            val image = controller.novelImage(url)
            val decoded = withContext(DesktopDispatchers.decode) { DesktopImageDecoder.decode(image.path).also { unpublished = it } }
            value = decoded.asComposeImageBitmap()
            unpublished = null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = desktopText("novel.imageFailed") }
        finally { unpublished?.close() }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Text(failure ?: desktopText("novel.imageLoading"), color = muted, fontSize = 13.sp)
        // Small pictures keep their own width instead of being stretched to the whole text column.
        else Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = image.width.coerceAtLeast(1).dp)
            .fillMaxWidth().heightIn(max = maxHeight ?: 900.dp).testTag("novel-image"))
    }
}

/** Outlined buttons follow the reading theme instead of the window theme. */
@Composable
private fun themedOutline(theme: DesktopNovelTheme) = ButtonDefaults.outlinedButtonColors(
    containerColor = Color.Transparent, contentColor = Color(theme.palette.text),
    disabledContentColor = Color(theme.palette.secondaryText).copy(alpha = .6f))

/** Share of the viewport the scrolling break band takes. */
private const val BREAK_BAND_FRACTION = 0.34f

/** The shared chapter break card for the novel reader: the finished chapter and the next one, information only. */
@Composable
private fun DesktopNovelBreakCard(
    state: DesktopAppState,
    novel: DesktopNovel,
    modifier: Modifier = Modifier,
) {
    val next = state.adjacentChapter(true)
    Box(modifier, contentAlignment = Alignment.Center) {
        org.skepsun.kototoro.core.ui.chapters.ChapterBreakCard(
            finishedTitle = novel.titleOf(novel.chapter),
            nextTitle = next?.let { novel.titleOf(it)?.takeIf(String::isNotBlank) ?: it.number.toString().removeSuffix(".0") },
        )
    }
}
