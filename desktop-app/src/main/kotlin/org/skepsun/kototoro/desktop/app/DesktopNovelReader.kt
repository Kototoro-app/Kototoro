package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeActions
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeLayout

private enum class NovelPanel { CHAPTERS, SETTINGS, BOOKMARKS }

/** Scrolling text reader for novel chapters: one block per paragraph, position kept as a block index. */
@Composable
internal fun DesktopNovelReader(controller: DesktopController, state: DesktopAppState, closing: Boolean,
    fullscreen: Boolean = false, onToggleFullscreen: (() -> Unit)? = null) {
    val novel = state.novel ?: return
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
    fun back() {
        if (fullscreen) onToggleFullscreen?.invoke()
        controller.backToDetails()
    }
    // Position first, then reporting: the first emission must not overwrite a restored position with block 0.
    LaunchedEffect(novel.chapter.id, novel.navigation) {
        list.scrollToItem(novel.startBlock)
        focus.requestFocus()
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index < novel.blocks.size }
            // Items behind the fixed top padding are composed too; they are not the saved reading anchor.
            list.firstVisibleItemIndex.coerceAtMost(novel.blocks.lastIndex.coerceAtLeast(0)) to
                (visible.lastOrNull()?.index ?: 0)
        }.collect { (first, last) -> controller.novelProgress(novel.chapter.id, first, last.coerceAtLeast(first)) }
    }
    CompositionLocalProvider(LocalContentColor provides Color(colors.text)) {
        Box(Modifier.fillMaxSize().background(Color(colors.background)).testTag("novel-reader")
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
                        scope.launch { list.animateScrollBy(list.layoutInfo.viewportSize.height * .9f) }; true
                    }
                    event.key == Key.PageUp || event.key == Key.Spacebar -> {
                        scope.launch { list.animateScrollBy(-list.layoutInfo.viewportSize.height * .9f) }; true
                    }
                    event.key == Key.MoveHome -> { scope.launch { list.scrollToItem(0) }; true }
                    event.key == Key.MoveEnd -> { scope.launch { list.scrollToItem(novel.blocks.lastIndex) }; true }
                    else -> false
                }
            }) {
            SelectionContainer(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                    focus.requestFocus()
                })
            }) {
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
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center) {
                            OutlinedButton({ controller.changeNovelChapter(false) }, enabled = enabled && hasPrevious,
                                colors = themedOutline(colors), modifier = Modifier.testTag("novel-previous-chapter")) { Text("上一章") }
                            Spacer(Modifier.width(16.dp))
                            Button({ controller.changeNovelChapter(true) }, enabled = enabled && hasNext,
                                modifier = Modifier.testTag("novel-next-chapter")) { Text("下一章") }
                        }
                    }
                }
            }
            DesktopNovelReaderChrome(
                novel = novel,
                theme = colors,
                title = state.content?.title.orEmpty(),
                controlsVisible = controlsVisible,
                panelVisible = panel != null,
                previousEnabled = enabled && hasPrevious,
                nextEnabled = enabled && hasNext,
                bookmarked = novel.firstVisible in novel.bookmarkBlocks.values,
                onToggleBookmark = { if (enabled) controller.toggleNovelBookmark() },
                onBookmarks = { panel = NovelPanel.BOOKMARKS },
                actions = NovelReaderChromeActions(
                    onBack = ::back,
                    onChapters = { panel = NovelPanel.CHAPTERS },
                    onOptions = { panel = NovelPanel.SETTINGS },
                    onProgressSelected = { block ->
                        if (enabled && block in novel.blocks.indices) {
                            scope.launch { list.scrollToItem(block) }
                            focus.requestFocus()
                        }
                    },
                    onPreviousChapter = { if (enabled) controller.changeNovelChapter(false) },
                    onNextChapter = { if (enabled) controller.changeNovelChapter(true) },
                ),
                onShowControls = { controlsVisible = true; focus.requestFocus() },
            )
            panel?.let { selected ->
                val close: () -> Unit = { panel = null; focus.requestFocus() }
                when (selected) {
                    NovelPanel.CHAPTERS -> DesktopNovelChaptersPanel(controller, state, enabled, close)
                    NovelPanel.BOOKMARKS -> DesktopNovelBookmarksPanel(controller, state, enabled, close)
                    NovelPanel.SETTINGS -> DesktopNovelOptionsPanel(controller, settings, fullscreen, onToggleFullscreen, close)
                }
            }
        }
    }
}

@Composable
private fun NovelBlockView(controller: DesktopController, block: NovelBlock, settings: DesktopNovelSettings) {
    val colors = settings.theme
    val family = if (settings.serif) FontFamily.Serif else FontFamily.Default
    when (block) {
        is NovelBlock.Paragraph -> Text(buildAnnotatedString {
            block.runs.forEach { run ->
                withStyle(SpanStyle(fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null)) { append(run.text) }
            }
        }, color = Color(colors.text), fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineSpacing).sp,
            fontFamily = family, modifier = Modifier.padding(bottom = (settings.fontSize * .7f).dp))
        is NovelBlock.Heading -> Text(block.text, color = Color(colors.text), fontFamily = family, fontWeight = FontWeight.Bold,
            fontSize = (settings.fontSize + (7 - block.level).coerceIn(1, 6) * 2).sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
        NovelBlock.Rule -> Divider(Modifier.padding(vertical = 16.dp), color = Color(colors.muted).copy(alpha = .4f))
        is NovelBlock.Image -> NovelInlineImage(controller, block.url, Color(colors.muted))
    }
}

@Composable
private fun NovelInlineImage(controller: DesktopController, url: String, muted: Color) {
    var failure by remember(url) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = null
        var unpublished: Bitmap? = null
        try {
            val image = controller.novelImage(url)
            val decoded = withContext(Dispatchers.IO) { DesktopImageDecoder.decode(image.path).also { unpublished = it } }
            value = decoded.asComposeImageBitmap()
            unpublished = null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = "插图加载失败" }
        finally { unpublished?.close() }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Text(failure ?: "正在加载插图…", color = muted, fontSize = 13.sp)
        // Small pictures keep their own width instead of being stretched to the whole text column.
        else Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = image.width.coerceAtLeast(1).dp)
            .fillMaxWidth().heightIn(max = 900.dp).testTag("novel-image"))
    }
}

/** Outlined buttons follow the reading theme instead of the window theme. */
@Composable
private fun themedOutline(theme: DesktopNovelTheme) = ButtonDefaults.outlinedButtonColors(
    backgroundColor = Color.Transparent, contentColor = Color(theme.text), disabledContentColor = Color(theme.muted).copy(alpha = .6f))
