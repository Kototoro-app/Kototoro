package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.skepsun.kototoro.desktop.runtime.NovelBlock

private enum class NovelPanel { CHAPTERS, SETTINGS }

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
    LaunchedEffect(novel.chapter.id) {
        list.scrollToItem(novel.startBlock)
        focus.requestFocus()
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index < novel.blocks.size }
            (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: 0)
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
            Column(Modifier.fillMaxSize()) {
                if (controlsVisible) Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ back() }) { Text("返回详情", color = Color(colors.text)) }
                    Column(Modifier.weight(1f)) {
                        Text(state.content?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(novel.chapter.title ?: "第 ${novel.chapter.number} 章", color = Color(colors.muted),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                    TextButton({ panel = if (panel == NovelPanel.CHAPTERS) null else NovelPanel.CHAPTERS },
                        modifier = Modifier.testTag("novel-chapters")) { Text("章节", color = Color(colors.text)) }
                    TextButton({ panel = if (panel == NovelPanel.SETTINGS) null else NovelPanel.SETTINGS },
                        modifier = Modifier.testTag("novel-settings")) { Text("阅读设置", color = Color(colors.text)) }
                    TextButton({ onToggleFullscreen?.invoke() }, enabled = onToggleFullscreen != null) {
                        Text(if (fullscreen) "退出全屏" else "全屏", color = Color(colors.text))
                    }
                    TextButton({ controlsVisible = false; panel = null; focus.requestFocus() }) {
                        Text("收起", color = Color(colors.text))
                    }
                }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("novel-list"), state = list,
                        contentPadding = PaddingValues(vertical = 24.dp)) {
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
                if (controlsVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ controller.changeNovelChapter(false) }, enabled = enabled && hasPrevious,
                        colors = themedOutline(colors)) { Text("上一章") }
                    Spacer(Modifier.weight(1f))
                    val percent = ((novel.lastVisible + 1) * 100) / novel.blocks.size
                    Text("第 ${novel.firstVisible + 1}–${novel.lastVisible + 1} / ${novel.blocks.size} 段 · $percent%",
                        color = Color(colors.muted), modifier = Modifier.testTag("novel-progress"))
                    Spacer(Modifier.weight(1f))
                    OutlinedButton({ controller.changeNovelChapter(true) }, enabled = enabled && hasNext,
                        colors = themedOutline(colors)) { Text("下一章") }
                }
            }
            if (!controlsVisible) TextButton({ controlsVisible = true; focus.requestFocus() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Text("${novel.firstVisible + 1} / ${novel.blocks.size} · 显示工具栏", color = Color(colors.muted))
            }
            panel?.let { selected ->
                val close: () -> Unit = { panel = null; focus.requestFocus() }
                if (selected == NovelPanel.CHAPTERS) {
                    DesktopReaderSidePanel(controller, state, DesktopReaderPanel.CHAPTERS, enabled,
                        Modifier.align(Alignment.CenterEnd).padding(top = 64.dp, bottom = 16.dp)) { close() }
                } else NovelSettingsPanel(controller, settings,
                    Modifier.align(Alignment.CenterEnd).padding(top = 64.dp, bottom = 16.dp), close)
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

@Composable
private fun NovelSettingsPanel(controller: DesktopController, settings: DesktopNovelSettings, modifier: Modifier,
    onClose: () -> Unit) {
    Surface(modifier.width(340.dp).fillMaxHeight().testTag("novel-settings-panel"), shape = RoundedCornerShape(16.dp),
        elevation = 12.dp) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阅读设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("关闭") }
            }
            Text("主题", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopNovelTheme.entries.forEach { theme ->
                    Surface(Modifier.size(56.dp, 40.dp).testTag("novel-theme:${theme.name}")
                        .clickable { controller.novelSettings(settings.copy(theme = theme)) },
                        color = Color(theme.background), shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            if (theme == settings.theme) 2.dp else 1.dp,
                            if (theme == settings.theme) Accent else Color(theme.muted).copy(alpha = .5f))) {
                        Box(contentAlignment = Alignment.Center) { Text(theme.title, color = Color(theme.text), fontSize = 11.sp) }
                    }
                }
            }
            SettingSlider("字号 ${settings.fontSize}", settings.fontSize.toFloat(), DesktopNovelSettings.FONT_SIZES.let { it.first.toFloat()..it.last.toFloat() },
                "novel-font-size") { controller.novelSettings(settings.copy(fontSize = it.toInt())) }
            SettingSlider("行距 ${"%.2f".format(settings.lineSpacing)}", settings.lineSpacing, DesktopNovelSettings.LINE_SPACINGS,
                "novel-line-spacing") { controller.novelSettings(settings.copy(lineSpacing = (it * 20).toInt() / 20f)) }
            SettingSlider("版心宽度 ${settings.width}", settings.width.toFloat(), DesktopNovelSettings.WIDTHS.let { it.first.toFloat()..it.last.toFloat() },
                "novel-width") { controller.novelSettings(settings.copy(width = (it.toInt() / 20) * 20)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("衬线字体", modifier = Modifier.weight(1f))
                Switch(settings.serif, { controller.novelSettings(settings.copy(serif = it)) },
                    modifier = Modifier.testTag("novel-serif"))
            }
            Divider()
            Text("快捷键", fontWeight = FontWeight.SemiBold)
            Text("PageUp / PageDown / 空格 翻页滚动\nHome / End 章首末\nH 收起或显示工具栏\nF11 全屏\nEsc 关闭面板或返回详情",
                fontSize = 13.sp, color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
        }
    }
}

@Composable
private fun SettingSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String,
    onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text(title)
        Slider(draft, { draft = it }, valueRange = range, onValueChangeFinished = { onChange(draft) },
            modifier = Modifier.testTag(tag))
    }
}

/** Outlined buttons follow the reading theme instead of the window theme. */
@Composable
private fun themedOutline(theme: DesktopNovelTheme) = ButtonDefaults.outlinedButtonColors(
    backgroundColor = Color.Transparent, contentColor = Color(theme.text), disabledContentColor = Color(theme.muted).copy(alpha = .6f))
