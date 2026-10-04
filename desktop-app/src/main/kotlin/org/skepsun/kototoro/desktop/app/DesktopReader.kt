package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import java.nio.file.Path

@Composable
internal fun DesktopReader(controller: DesktopController, state: DesktopAppState, closing: Boolean,
    fullscreen: Boolean = false, onToggleFullscreen: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<DesktopReaderPanel?>(null) }
    val enabled = !state.busy && !closing
    fun back() {
        if (fullscreen) onToggleFullscreen?.invoke()
        controller.backToDetails()
    }
    val settings = state.readerSettings
    val continuous = settings.mode == DesktopReaderMode.CONTINUOUS
    val layout = remember(state.pages, state.chapter, state.readerImages, state.readerGeometry, settings,
        state.pageIndex) {
        DesktopReaderLayout(state.pages, state.chapter?.id ?: 0L, state.readerImages, settings, state.pageIndex,
            geometry = state.readerGeometry)
    }
    val hasPreviousChapter = state.adjacentChapter(false) != null
    val hasNextChapter = state.adjacentChapter(true) != null
    val previousBoundary = if (continuous) state.readerScrollReady && state.pageIndex == 0 && state.readerScroll == 0f
        else layout.turnIndex(false) == null
    val nextBoundary = if (continuous) state.readerScrollReady && state.readerAtEnd else layout.turnIndex(true) == null
    LaunchedEffect(state.chapter?.id, settings.mode) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color(0xFF15191F)).testTag("reader-surface").onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            false
        } else {
            if (event.key == Key.H && panel == null) {
                controlsVisible = !controlsVisible
                panel = null
                return@onPreviewKeyEvent true
            }
            if (event.key == Key.F11 && onToggleFullscreen != null) {
                onToggleFullscreen()
                return@onPreviewKeyEvent true
            }
            if (event.key == Key.Escape && panel != null) {
                panel = null
                focus.requestFocus()
                return@onPreviewKeyEvent true
            }
            // Chapter search and panel controls own their editing keys; never turn pages while typing.
            if (panel != null) return@onPreviewKeyEvent false
            if (event.key == Key.B) {
                if (enabled) controller.toggleBookmark()
                return@onPreviewKeyEvent true
            }
            val command: (() -> Unit)? = when (event.key) {
                Key.DirectionRight -> { { controller.turnPage(!settings.rightToLeft) } }
                Key.DirectionLeft -> { { controller.turnPage(settings.rightToLeft) } }
                Key.PageDown -> { { controller.turnPage(true) } }
                Key.PageUp -> { { controller.turnPage(false) } }
                Key.Spacebar -> { { controller.turnPage(!event.isShiftPressed) } }
                Key.MoveHome -> { { controller.page(0) } }
                Key.MoveEnd -> { { controller.page(state.pages.lastIndex) } }
                Key.Escape -> { { back() } }
                else -> null
            }
            if (continuous && event.key != Key.Escape) return@onPreviewKeyEvent false
            // Consume reader shortcuts while busy so repeated key events cannot enqueue stale page turns.
            if (enabled) command?.invoke()
            command != null
        }
    }) {
        ReaderTheme {
            Column(Modifier.fillMaxSize()) {
                if (controlsVisible) Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ back() }, enabled = enabled) { Text("返回详情") }
                    Column(Modifier.weight(1f)) {
                        Text(state.content?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(state.chapter?.title ?: "阅读", color = MaterialTheme.colors.onSurface.copy(alpha = .65f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                    TextButton({ panel = if (panel == DesktopReaderPanel.CHAPTERS) null
                        else DesktopReaderPanel.CHAPTERS },
                        modifier = Modifier.testTag("reader-chapters")) { Text("章节") }
                    TextButton({ panel = if (panel == DesktopReaderPanel.BOOKMARKS) null
                        else DesktopReaderPanel.BOOKMARKS },
                        modifier = Modifier.testTag("reader-bookmarks")) { Text("书签") }
                    TextButton({ panel = if (panel == DesktopReaderPanel.OPTIONS) null
                        else DesktopReaderPanel.OPTIONS },
                        modifier = Modifier.testTag("reader-options")) { Text("阅读设置") }
                    TextButton({ onToggleFullscreen?.invoke() }, enabled = onToggleFullscreen != null,
                        modifier = Modifier.testTag("reader-fullscreen")) { Text(if (fullscreen) "退出全屏" else "全屏") }
                    TextButton({ controlsVisible = false; panel = null; focus.requestFocus() },
                        modifier = Modifier.testTag("reader-hide-controls")) { Text("收起") }
                }
                if (continuous) DesktopScrollReader(controller, state, focus, closing,
                    Modifier.weight(1f).fillMaxWidth())
                else DesktopReaderCanvas(controller, state, focus, enabled,
                    Modifier.weight(1f).fillMaxWidth(), controlsVisible)
                if (controlsVisible) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ controller.changeChapter(false) }, enabled = enabled && hasPreviousChapter) {
                            Text("上一章")
                        }
                        OutlinedButton({ controller.turnPage(false) }, enabled = enabled &&
                            ((if (continuous) state.pageIndex > 0 else layout.turnIndex(false) != null) ||
                                (settings.automaticChapter && previousBoundary && hasPreviousChapter)),
                            modifier = Modifier.testTag("reader-backward")) { Text("上一页") }
                        Spacer(Modifier.weight(1f))
                        val visible = if (continuous) (state.pageIndex..state.readerLastVisible).toList()
                            else layout.indices
                        val range = if (visible.size > 1) "${visible.first() + 1}–${visible.last() + 1}"
                            else "${state.pageIndex + 1}"
                        Text("$range / ${state.pages.size}", modifier = Modifier.testTag("reader-progress"))
                        Spacer(Modifier.weight(1f))
                        Button({ controller.turnPage(true) }, enabled = enabled &&
                            ((if (continuous) !state.readerAtEnd && state.pageIndex < state.pages.lastIndex
                                else layout.turnIndex(true) != null) ||
                                    (settings.automaticChapter && nextBoundary && hasNextChapter)),
                            modifier = Modifier.testTag("reader-forward")) { Text("下一页") }
                        OutlinedButton({ controller.changeChapter(true) }, enabled = enabled && hasNextChapter) {
                            Text("下一章")
                        }
                    }
                    var targetPage by remember(state.chapter?.id, state.pageIndex) {
                        mutableFloatStateOf(state.pageIndex.toFloat())
                    }
                    Slider(targetPage, { targetPage = it },
                        valueRange = 0f..state.pages.lastIndex.coerceAtLeast(1).toFloat(),
                        onValueChangeFinished = {
                            val target = targetPage.toInt().coerceIn(0, state.pages.lastIndex.coerceAtLeast(0))
                            if (enabled && target != state.pageIndex) controller.page(target)
                            focus.requestFocus()
                        }, enabled = enabled && state.pages.size > 1, modifier = Modifier.fillMaxWidth().height(28.dp)
                            .testTag("reader-page-slider"))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ controller.readerSettings(settings.copy(mode =
                            DesktopReaderMode.entries[(settings.mode.ordinal + 1) % DesktopReaderMode.entries.size])) },
                            enabled = enabled, modifier = Modifier.testTag("reader-mode")) {
                            Text(modeTitle(settings.mode))
                        }
                        OutlinedButton({ controller.readerSettings(settings.copy(
                            rightToLeft = !settings.rightToLeft)) },
                            enabled = enabled && !continuous, modifier = Modifier.testTag("reader-direction")) {
                            Text(if (continuous) "从上到下" else if (settings.rightToLeft) "从右向左" else "从左向右")
                        }
                        OutlinedButton({ focus.requestFocus()
                            controller.readerSettings(settings.copy(automaticChapter = !settings.automaticChapter)) },
                            enabled = enabled, modifier = Modifier.testTag("reader-auto-chapter")) {
                            Text(if (settings.automaticChapter) "自动跨章：开" else "自动跨章：关")
                        }
                        Spacer(Modifier.weight(1f))
                        val bookmarked = state.bookmarks.any {
                            it.chapterId == state.chapter?.id && it.page == state.pageIndex
                        }
                        TextButton({ controller.toggleBookmark() }, enabled = enabled &&
                            (!continuous || state.readerScrollReady), modifier = Modifier.testTag("reader-bookmark-toggle")) {
                            Text(if (bookmarked) "移除书签" else "添加书签")
                        }
                        TextButton({ controller.reloadPage() }, enabled = enabled) { Text("重新加载") }
                    }
                }
            }
            if (!controlsVisible) TextButton({ controlsVisible = true; focus.requestFocus() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("reader-show-controls")) {
                Text("${state.pageIndex + 1} / ${state.pages.size} · 显示工具栏")
            }
            panel?.let { selected ->
                DesktopReaderSidePanel(controller, state, selected, enabled,
                    Modifier.align(Alignment.CenterEnd).padding(top = 64.dp, bottom = 16.dp)) {
                    panel = null; focus.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun ReaderTheme(content: @Composable () -> Unit) {
    val accent = Color(0xFF9CDCCD)
    MaterialTheme(colors = darkColors(primary = accent, secondary = accent,
        onPrimary = Color(0xFF133F35), onSecondary = Color(0xFF133F35),
        background = Color(0xFF15191F), surface = Color(0xFF232933))) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface, content = content)
    }
}

@Composable
internal fun ReaderImage(path: Path?, pageIndex: Int, modifier: Modifier) {
    var failure by remember(path) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        var unpublished: Bitmap? = null
        if (path != null) try {
            val decoded = withContext(Dispatchers.IO) {
                DesktopImageDecoder.decode(path).also { unpublished = it }
            }
            // Compose adopts this bounded bitmap. Close only unpublished results on cancellation/failure.
            value = decoded.asComposeImageBitmap()
            unpublished = null
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failure = "页面解码失败，请重新加载" }
        finally { unpublished?.close() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Text(failure ?: "正在准备第 ${pageIndex + 1} 页…", color = Color.White)
        else Image(image, "第 ${pageIndex + 1} 页", contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().testTag("reader-page"))
    }
}
