package org.skepsun.kototoro.desktop.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChromeTopBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderFloatingControlButton
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressDock

// Android's reader chrome geometry (ComposeReaderActivityScaffold).
private val TopImmersiveHeight = 76.dp
private val BottomImmersiveHeight = 84.dp
private val TopFeatherExtension = 72.dp
private val BottomFeatherExtension = 48.dp
private val FloatingControlInset = 62.dp
private val TopImmersiveStops = listOf(0f, 0.24f, 0.50f, 0.70f, 0.86f, 1f)
private val BottomImmersiveStops = listOf(0f, 0.18f, 0.38f, 0.70f, 1f)

/**
 * Android's reader chrome over the Windows reader, from the shared core-ui pieces: the top bar (back, title chip
 * opening the chapters, options), soft edge gradients, the progress dock (previous chapter, page slider, next chapter)
 * and the floating controls (bookmark — long press opens the bookmarks — and auto scroll). With the chrome hidden,
 * Android's info bar shows the chapter and page.
 */
@Composable
internal fun BoxScope.DesktopReaderChrome(
    controller: DesktopController,
    state: DesktopAppState,
    controlsVisible: Boolean,
    enabled: Boolean,
    continuous: Boolean,
    layout: DesktopReaderLayout,
    autoScroll: DesktopReaderAutoScroll,
    onBack: () -> Unit,
    onChapters: () -> Unit,
    onOptions: () -> Unit,
    onBookmarks: () -> Unit,
    onInteraction: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val immersiveBase = if (dark) Color.Black else Color.White
    val chromeText = if (dark) Color.White else Color.Black
    AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ImmersiveEdgeGradient(
                height = TopImmersiveHeight + TopFeatherExtension,
                colors = listOf(immersiveBase.copy(alpha = .86f), immersiveBase.copy(alpha = .62f),
                    immersiveBase.copy(alpha = .32f), immersiveBase.copy(alpha = .12f), immersiveBase.copy(alpha = .035f),
                    immersiveBase.toTransparentImmersiveColor()),
                stops = TopImmersiveStops,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
            ImmersiveEdgeGradient(
                height = BottomImmersiveHeight + BottomFeatherExtension,
                colors = listOf(immersiveBase.toTransparentImmersiveColor(), immersiveBase.copy(alpha = .035f),
                    immersiveBase.copy(alpha = .16f), immersiveBase.copy(alpha = .42f), immersiveBase.copy(alpha = .78f)),
                stops = BottomImmersiveStops,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
    AnimatedVisibility(controlsVisible, enter = slideInVertically { -it }, exit = slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter)) {
        ReaderChromeTopBar(
            title = state.content?.title.orEmpty(),
            subtitle = state.chapter?.title.orEmpty(),
            chapterTitleAtBottom = false,
            backDescription = AndroidStrings["back"],
            optionsDescription = AndroidStrings["options"],
            onNavigateBack = onBack,
            onChapters = onChapters,
            onOptions = onOptions,
            contentColor = chromeText,
        )
    }
    AnimatedVisibility(controlsVisible, enter = slideInVertically { it }, exit = slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter)) {
        Box(Modifier.fillMaxWidth()) {
            if (state.pages.size > 1) ReaderProgressDock(
                isIosStyle = false,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                var target by remember(state.chapter?.id, state.pageIndex) { mutableStateOf<Int?>(null) }
                ReaderProgressBar(
                    value = state.pageIndex.toFloat(),
                    max = state.pages.lastIndex.toFloat(),
                    onValueChange = { target = it.toInt().coerceIn(0, state.pages.lastIndex) },
                    onValueChangeFinished = {
                        target?.let { if (enabled && it != state.pageIndex) controller.page(it) }
                        target = null
                        onInteraction()
                    },
                    onPreviousChapter = { controller.changeChapter(false) },
                    onNextChapter = { controller.changeChapter(true) },
                    previousDescription = AndroidStrings["prev_chapter"],
                    nextDescription = AndroidStrings["next_chapter"],
                    previousEnabled = enabled && state.adjacentChapter(false) != null,
                    nextEnabled = enabled && state.adjacentChapter(true) != null,
                    isIosStyle = false,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = FloatingControlInset),
            ) {
                val bookmarked = state.bookmarks.any { it.chapterId == state.chapter?.id && it.page == state.pageIndex }
                val bookmarkLabel = AndroidStrings[if (bookmarked) "bookmark_remove" else "bookmark_add"]
                ReaderFloatingControlButton(
                    icon = rememberVectorPainter(if (bookmarked) ReaderPanelIcons.BookmarkAdded else ReaderPanelIcons.Bookmark),
                    contentDescription = bookmarkLabel,
                    label = bookmarkLabel,
                    active = bookmarked,
                    showLabel = false,
                    onClick = { if (enabled && (!continuous || state.readerScrollReady)) controller.toggleBookmark() },
                    onLongClick = onBookmarks,
                    modifier = Modifier.testTag("reader-bookmark-toggle"),
                )
                ReaderFloatingControlButton(
                    icon = rememberVectorPainter(if (autoScroll.active) ReaderPanelIcons.TimerRun else ReaderPanelIcons.Timer),
                    contentDescription = AndroidStrings["automatic_scroll"],
                    label = AndroidStrings["automatic_scroll"],
                    active = autoScroll.active,
                    showLabel = false,
                    onClick = { autoScroll.active = !autoScroll.active; onInteraction() },
                    modifier = Modifier.testTag("reader-autoscroll"),
                )
            }
        }
    }
    // Android's info bar while the chrome is hidden: the chapter and the visible pages.
    AnimatedVisibility(!controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
        val visible = if (continuous) (state.pageIndex..state.readerLastVisible).toList() else layout.indices
        val range = if (visible.size > 1) "${visible.first() + 1}–${visible.last() + 1}" else "${state.pageIndex + 1}"
        val color = LocalDesktopReaderPageStyle.current.text.copy(alpha = .78f)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)
            .testTag("reader-info-bar")) {
            Text(state.chapter?.title.orEmpty(), color = color, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("$range / ${state.pages.size}", color = color, fontSize = 12.sp,
                modifier = Modifier.testTag("reader-progress"))
        }
    }
}
