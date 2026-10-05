package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
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
    val autoScroll = remember { DesktopReaderAutoScroll() }
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
    LaunchedEffect(state.chapter?.id, settings.mode) { focus.requestFocus() }
    val pageStyle = rememberReaderPageStyle(settings, state.appearance)
    CompositionLocalProvider(LocalDesktopReaderPageStyle provides pageStyle) {
    Box(Modifier.fillMaxSize().background(pageStyle.background).testTag("reader-surface").onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) autoScroll.interacted()
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
                Key.DirectionRight -> { { controller.turnPage(settings.vertical || !settings.rightToLeft) } }
                Key.DirectionLeft -> { { controller.turnPage(!settings.vertical && settings.rightToLeft) } }
                // Vertical paging turns with the up / down arrows too.
                Key.DirectionDown -> if (settings.vertical) { { controller.turnPage(true) } } else null
                Key.DirectionUp -> if (settings.vertical) { { controller.turnPage(false) } } else null
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
            val onToggleControls: () -> Unit = { controlsVisible = !controlsVisible; panel = null; focus.requestFocus() }
            // Android's "show menu" action opens the reader options with the controls.
            val showMenu = { controlsVisible = true; panel = DesktopReaderPanel.OPTIONS }
            if (continuous) DesktopScrollReader(controller, state, focus, closing, Modifier.fillMaxSize(),
                onToggleControls = onToggleControls, autoScroll, showMenu)
            else DesktopReaderCanvas(controller, state, focus, enabled, Modifier.fillMaxSize(), controlsVisible,
                onToggleControls = onToggleControls, autoScroll, showMenu)
            DesktopReaderChrome(controller, state, controlsVisible, enabled, continuous, layout, autoScroll,
                onBack = ::back, onChapters = { panel = DesktopReaderPanel.CHAPTERS },
                onOptions = { panel = DesktopReaderPanel.OPTIONS }, onBookmarks = { panel = DesktopReaderPanel.BOOKMARKS },
                onInteraction = { focus.requestFocus() })
            when (val selected = panel) {
                null -> Unit
                // Android's reader options panel, shared through core-ui.
                DesktopReaderPanel.OPTIONS -> DesktopReaderOptionsPanel(controller, state, fullscreen,
                    onToggleFullscreen, autoScroll, onDismiss = { panel = null; focus.requestFocus() },
                    onOpenChapters = { panel = DesktopReaderPanel.CHAPTERS },
                    onOpenSettings = { panel = DesktopReaderPanel.MORE })
                else -> DesktopReaderSidePanel(controller, state, selected, enabled,
                    Modifier.align(Alignment.CenterEnd).padding(top = 64.dp, bottom = 16.dp)) {
                    panel = null; focus.requestFocus()
                }
            }
        }
    }
    }
}

/**
 * Runs a reader action from the tap grid, as Android's `ReaderControlDelegate.processAction`: [page] moves a page
 * (or a screen in the continuous reader) forward or back.
 */
internal fun performTapAction(action: org.skepsun.kototoro.reader.ui.tapgrid.TapAction?, page: (Boolean) -> Unit,
    controller: DesktopController, onToggleControls: () -> Unit, onShowMenu: () -> Unit) {
    when (action) {
        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.PAGE_NEXT -> page(true)
        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.PAGE_PREV -> page(false)
        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.CHAPTER_NEXT -> controller.changeChapter(true)
        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.CHAPTER_PREV -> controller.changeChapter(false)
        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.TOGGLE_UI -> onToggleControls()
        org.skepsun.kototoro.reader.ui.tapgrid.TapAction.SHOW_MENU -> onShowMenu()
        null -> Unit
    }
}

/** Tap-grid callbacks that stay the same across recompositions, so a long press in progress is never restarted. */
internal class DesktopTapGridHandlers(
    val interaction: () -> Unit,
    val tap: (org.skepsun.kototoro.reader.domain.TapGridArea) -> Unit,
    val longTap: (org.skepsun.kototoro.reader.domain.TapGridArea, androidx.compose.ui.geometry.Offset,
        androidx.compose.ui.unit.IntSize) -> Unit,
)

@Composable
internal fun rememberTapGridHandlers(focus: FocusRequester, autoScroll: DesktopReaderAutoScroll,
    onAction: (org.skepsun.kototoro.reader.domain.TapGridArea, Boolean) -> Unit): DesktopTapGridHandlers {
    val current by rememberUpdatedState(onAction)
    return remember(focus, autoScroll) {
        DesktopTapGridHandlers({ focus.requestFocus(); autoScroll.interacted() }, { area -> current(area, false) },
            { area, _, _ -> current(area, true) })
    }
}

/** Auto scroll state shared by the readers: interactions pause it (Android's `ScrollTimer` pause). */
internal class DesktopReaderAutoScroll {
    var active by mutableStateOf(false)
    @Volatile private var interactedAt = 0L

    fun interacted() { interactedAt = System.currentTimeMillis() }

    val isPaused: Boolean get() =
        System.currentTimeMillis() < interactedAt + org.skepsun.kototoro.reader.core.ReaderAutoScroll.INTERACTION_PAUSE_MS
}

/** How pages are presented: the reader background and Android's colour correction. */
internal data class DesktopReaderPageStyle(val background: Color, val colorFilter: ColorFilter?, val text: Color)

internal val LocalDesktopReaderPageStyle = staticCompositionLocalOf {
    DesktopReaderPageStyle(Color(0xFF15191F), null, Color.White)
}

/** Background colours of Android's reader backgrounds; the book effect tints light ones as Android's tint does. */
@Composable
internal fun rememberReaderPageStyle(settings: DesktopReaderSettings, appearance: DesktopAppearance): DesktopReaderPageStyle {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val appDark = when (appearance) { DesktopAppearance.LIGHT -> false; DesktopAppearance.DARK -> true; else -> systemDark }
    return remember(settings.background, settings.colorFilter, appDark) {
        val base = when (settings.background) {
            DesktopReaderBackground.DEFAULT -> Color(0xFF15191F)
            DesktopReaderBackground.AUTO -> if (appDark) Color(0xFF15191F) else Color(0xFFF4F2EE)
            DesktopReaderBackground.LIGHT -> Color(0xFFF4F2EE)
            DesktopReaderBackground.DARK -> Color(0xFF1F1F23)
            DesktopReaderBackground.WHITE -> Color.White
            DesktopReaderBackground.BLACK -> Color.Black
        }
        val light = base.luminance() > .5f
        val background = if (settings.colorFilter.book && light) Color(base.red, base.green,
            base.blue * org.skepsun.kototoro.reader.domain.ReaderColorMatrix.BOOK_BLUE_FACTOR) else base
        DesktopReaderPageStyle(background,
            settings.colorFilter.takeUnless { it.isEmpty }?.let { ColorFilter.colorMatrix(ColorMatrix(it.matrix())) },
            if (light) Color(0xFF30333A) else Color.White)
    }
}

@Composable
private fun ReaderTheme(content: @Composable () -> Unit) {
    val accent = Color(0xFFA9C7FF)
    MaterialTheme(colors = darkColors(primary = accent, secondary = accent,
        onPrimary = Color(0xFF003062), onSecondary = Color(0xFF003062),
        background = Color(0xFF15191F), surface = Color(0xFF232933))) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface, content = content)
    }
}

@Composable
internal fun ReaderImage(page: DesktopReaderImage?, pageIndex: Int, modifier: Modifier) {
    val path = page?.path
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
    val style = LocalDesktopReaderPageStyle.current
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Text(failure ?: "正在准备第 ${pageIndex + 1} 页…", color = style.text)
        else {
            // A cropped page shows only its content bounds; the decode may be downscaled, so scale them to it.
            val crop = page?.crop?.takeIf { page.width > 0 && page.height > 0 }
            val painter = remember(image, crop) {
                if (crop == null) androidx.compose.ui.graphics.painter.BitmapPainter(image) else {
                    val sx = image.width.toFloat() / page.width
                    val sy = image.height.toFloat() / page.height
                    val left = (crop.left * sx).toInt().coerceIn(0, image.width - 1)
                    val top = (crop.top * sy).toInt().coerceIn(0, image.height - 1)
                    androidx.compose.ui.graphics.painter.BitmapPainter(image,
                        androidx.compose.ui.unit.IntOffset(left, top),
                        androidx.compose.ui.unit.IntSize((crop.width * sx).toInt().coerceIn(1, image.width - left),
                            (crop.height * sy).toInt().coerceIn(1, image.height - top)))
                }
            }
            Image(painter, "第 ${pageIndex + 1} 页", contentScale = ContentScale.FillBounds,
                colorFilter = style.colorFilter, modifier = Modifier.fillMaxSize().testTag("reader-page"))
        }
    }
}
