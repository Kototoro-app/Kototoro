package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChapterTitleChip
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChromeTopBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressDock
import org.skepsun.kototoro.reader.ui.compose.resolveReaderChromeVisibility
import org.skepsun.kototoro.reader.ui.compose.withReaderChromeAnimations

object NovelReaderChromeLayout {
    val TopHeight = 76.dp
    val BottomHeight = 84.dp
}

private val TopGradientExtension = 72.dp
private val BottomGradientExtension = 48.dp
private val TopGradientStops = listOf(0f, 0.24f, 0.50f, 0.70f, 0.86f, 1f)
private val BottomGradientStops = listOf(0f, 0.18f, 0.38f, 0.70f, 1f)

/** Progress units belong to the engine: Android pages, desktop paragraph blocks. */
@Immutable
data class NovelReaderChromeState(
    val workTitle: String,
    val chapterTitle: String,
    val controlsVisible: Boolean,
    val progressValue: Float,
    val progressMax: Float,
    val progressLabel: String,
    val previousEnabled: Boolean,
    val nextEnabled: Boolean,
    val chapterTitleAtBottom: Boolean = false,
    val panelVisible: Boolean = false,
    val showReadingStatus: Boolean = false,
    val transparentReadingStatus: Boolean = true,
    val statusHorizontalPadding: Dp = 0.dp,
)

@Immutable
data class NovelReaderChromeColors(val background: Color, val content: Color, val secondary: Color)

@Immutable
data class NovelReaderChromeLabels(val back: String, val options: String, val previous: String, val next: String)

data class NovelReaderChromeActions(
    val onBack: () -> Unit,
    val onChapters: () -> Unit,
    val onOptions: () -> Unit,
    val onProgressSelected: (Int) -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
)

/** Chrome takes its surfaces from the reading palette, independently of the app's light/dark mode. */
fun novelChromeColorScheme(base: ColorScheme, colors: NovelReaderChromeColors): ColorScheme = base.copy(
    surface = colors.background,
    surfaceBright = colors.background,
    surfaceDim = colors.background,
    surfaceContainerLowest = colors.background,
    surfaceContainerLow = colors.background,
    surfaceContainer = colors.background,
    surfaceContainerHigh = colors.background,
    surfaceContainerHighest = colors.background,
    surfaceVariant = colors.background,
    onSurface = colors.content,
    onSurfaceVariant = colors.secondary,
    primary = colors.content,
    onPrimary = colors.background,
)

fun shouldShowNovelTopChapterTitle(controlsVisible: Boolean, chapterTitleAtBottom: Boolean): Boolean =
    controlsVisible && !chapterTitleAtBottom

@Composable
fun NovelReaderTopChromeContent(
    state: NovelReaderChromeState,
    colors: NovelReaderChromeColors,
    labels: NovelReaderChromeLabels,
    actions: NovelReaderChromeActions,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    val topHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + NovelReaderChromeLayout.TopHeight
    AnimatedVisibility(
        visible = state.controlsVisible,
        enter = slideInVertically { -it }.withReaderChromeAnimations(animationsEnabled),
        exit = slideOutVertically { -it }.withReaderChromeAnimations(animationsEnabled),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().height(topHeight + TopGradientExtension).testTag("novel-top-chrome")) {
            ImmersiveEdgeGradient(
                height = topHeight + TopGradientExtension,
                colors = listOf(
                    colors.background.copy(alpha = .86f), colors.background.copy(alpha = .62f),
                    colors.background.copy(alpha = .32f), colors.background.copy(alpha = .12f),
                    colors.background.copy(alpha = .035f), colors.background.toTransparentImmersiveColor(),
                ),
                stops = TopGradientStops,
                modifier = Modifier.fillMaxWidth(),
            )
            ReaderChromeTopBar(
                title = state.workTitle,
                subtitle = state.chapterTitle,
                chapterTitleAtBottom = !shouldShowNovelTopChapterTitle(
                    state.controlsVisible, state.chapterTitleAtBottom,
                ),
                backDescription = labels.back,
                optionsDescription = labels.options,
                onNavigateBack = actions.onBack,
                onChapters = actions.onChapters,
                onOptions = actions.onOptions,
                contentColor = colors.content,
            )
        }
    }
}

/** Geometry and transitions are shared; the host only supplies supported floating actions. */
@Composable
fun NovelReaderBottomChromeContent(
    state: NovelReaderChromeState,
    colors: NovelReaderChromeColors,
    labels: NovelReaderChromeLabels,
    actions: NovelReaderChromeActions,
    modifier: Modifier = Modifier,
    isIosStyle: Boolean = false,
    animationsEnabled: Boolean = true,
    floatingControls: (@Composable () -> Unit)? = null,
    onShowControls: (() -> Unit)? = null,
    navigationBarBottomInset: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
) {
    val navigationInset = navigationBarBottomInset
    val bottomHeight = NovelReaderChromeLayout.BottomHeight + BottomGradientExtension + navigationInset
    val progressAvailable = state.progressMax > 0f
    val visibility = resolveReaderChromeVisibility(
        controlsVisible = state.controlsVisible,
        progressAvailable = progressAvailable,
        chapterTitleAtBottom = state.chapterTitleAtBottom,
        floatingControlsAvailable = floatingControls != null,
    )
    Box(contentAlignment = Alignment.BottomCenter, modifier = modifier.fillMaxWidth().height(bottomHeight)) {
        AnimatedVisibility(
            visible = visibility.visible,
            enter = slideInVertically { it }.withReaderChromeAnimations(animationsEnabled),
            exit = slideOutVertically { it }.withReaderChromeAnimations(animationsEnabled),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(Modifier.fillMaxWidth().height(bottomHeight).testTag("novel-bottom-chrome")) {
                if (progressAvailable || state.chapterTitleAtBottom) {
                    ImmersiveEdgeGradient(
                        height = bottomHeight,
                        colors = listOf(
                            colors.background.toTransparentImmersiveColor(), colors.background.copy(alpha = .035f),
                            colors.background.copy(alpha = .16f), colors.background.copy(alpha = .42f),
                            colors.background.copy(alpha = .78f),
                        ),
                        stops = BottomGradientStops,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (progressAvailable) {
                    ReaderProgressDock(
                        isIosStyle = isIosStyle,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = navigationInset + 4.dp),
                        contentColor = colors.content,
                    ) {
                        var selectedValue by remember(state.progressValue) { mutableFloatStateOf(state.progressValue) }
                        ReaderProgressBar(
                            value = selectedValue,
                            max = state.progressMax,
                            onValueChange = { selectedValue = it },
                            onValueChangeFinished = { actions.onProgressSelected(selectedValue.toInt()) },
                            onPreviousChapter = actions.onPreviousChapter,
                            onNextChapter = actions.onNextChapter,
                            previousDescription = labels.previous,
                            nextDescription = labels.next,
                            previousEnabled = state.previousEnabled,
                            nextEnabled = state.nextEnabled,
                            isIosStyle = isIosStyle,
                        )
                    }
                }
                if (state.chapterTitleAtBottom) {
                    ReaderChapterTitleChip(
                        title = state.workTitle,
                        subtitle = state.chapterTitle,
                        onClick = actions.onChapters,
                        height = 44.dp,
                        contentColor = colors.content,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                            .padding(bottom = navigationInset + 62.dp),
                    )
                }
                if (floatingControls != null) {
                    AnimatedVisibility(
                        visible = !state.panelVisible,
                        enter = slideInVertically { it }.withReaderChromeAnimations(animationsEnabled),
                        exit = slideOutVertically { it }.withReaderChromeAnimations(animationsEnabled),
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                            .padding(end = 16.dp, bottom = 62.dp),
                    ) {
                        floatingControls()
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !state.controlsVisible && state.showReadingStatus,
            enter = fadeIn().withReaderChromeAnimations(animationsEnabled),
            exit = fadeOut().withReaderChromeAnimations(animationsEnabled),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val textColor = colors.content.copy(alpha = .78f)
            Surface(
                color = if (state.transparentReadingStatus) Color.Transparent else colors.background.copy(alpha = .72f),
                contentColor = textColor,
                modifier = Modifier.fillMaxWidth().testTag("novel-reading-status")
                    .then(if (onShowControls != null) Modifier.clickable(onClick = onShowControls) else Modifier),
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        start = state.statusHorizontalPadding, top = 5.dp,
                        end = state.statusHorizontalPadding, bottom = navigationInset + 5.dp,
                    ),
                ) {
                    Text(
                        text = state.chapterTitle, color = textColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (state.progressLabel.isNotBlank()) Text(
                        text = state.progressLabel, color = textColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        modifier = Modifier.padding(start = 10.dp).testTag("novel-progress"),
                    )
                }
            }
        }
    }
}

@Composable
fun NovelReaderFloatingControls(showLabels: Boolean, content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (showLabels) Modifier.width(IntrinsicSize.Max).widthIn(max = 200.dp) else Modifier,
        content = { content() },
    )
}
