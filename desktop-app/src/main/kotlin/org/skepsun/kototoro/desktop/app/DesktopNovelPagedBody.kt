package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeLayout

/**
 * Paged reading for the Windows novel reader.
 *
 * Blocks are measured with the same [TextMeasurer] and the same styles the page renders with, so page breaks are exact
 * without composing the whole chapter. Only the shown page is in the tree; the mouse wheel and the paging keys turn
 * pages, and the position stays a block index the scroll reader also understands.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopNovelPagedBody(
    controller: DesktopController,
    novel: DesktopNovel,
    settings: DesktopNovelSettings,
    pager: DesktopNovelPagerState,
    onFlip: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val styles = remember(settings) { settings.desktopNovelStyles() }
    val density = LocalDensity.current
    val wheel = remember { mutableLongStateOf(0L) }
    BoxWithConstraints(
        modifier.testTag("novel-paged").clipToBounds().onPointerEvent(PointerEventType.Scroll) { event ->
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (delta == 0f) return@onPointerEvent
            // A trackpad sends bursts: one page per beat, so a fast flick does not skip a page.
            val now = System.currentTimeMillis()
            if (now - wheel.longValue < WheelRepeatMillis) return@onPointerEvent
            wheel.longValue = now
            event.changes.forEach { it.consume() }
            onFlip(delta > 0f)
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        val pageWidth = min(maxWidth - HorizontalPadding * 2, settings.width.dp).coerceAtLeast(1.dp)
        val pageHeight = (maxHeight - NovelReaderChromeLayout.TopHeight - NovelReaderChromeLayout.BottomHeight -
            VerticalPadding * 2).coerceAtLeast(1.dp)
        val widthPx = with(density) { pageWidth.roundToPx() }
        val heightPx = with(density) { pageHeight.roundToPx() }
        val metrics = remember(density, settings) { DesktopNovelMetrics(density, settings) }
        val heights = remember(novel.blocks, styles, widthPx, heightPx, metrics) {
            desktopNovelBlockHeights(novel.blocks, measurer, styles, widthPx, heightPx, metrics)
        }
        val pages = remember(heights, heightPx) { desktopNovelPages(heights, heightPx) }
        // Reflows settle in this pass: the anchored block decides which page of the new layout is shown.
        pager.rebind(pages)
        val range = pager.range
        // The page reserves the chrome it would scroll under: a page never starts behind the toolbar.
        Column(Modifier.fillMaxSize().padding(start = HorizontalPadding, end = HorizontalPadding,
            top = NovelReaderChromeLayout.TopHeight + VerticalPadding,
            bottom = NovelReaderChromeLayout.BottomHeight + VerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
            Column(Modifier.widthIn(max = settings.width.dp).fillMaxWidth()
                .heightIn(max = pageHeight).clipToBounds().testTag("novel-page")) {
                range?.forEach { index ->
                    Box(Modifier.fillMaxWidth().testTag("novel-block:$index"), contentAlignment = Alignment.TopCenter) {
                        NovelBlockView(controller, novel.blocks[index], settings, styles, pageHeight)
                    }
                }
            }
        }
    }
}

/** The paddings the scroll reader also uses, kept beside the measurement so a page and its blocks agree. */
private val HorizontalPadding = 28.dp
private val VerticalPadding = 24.dp
private const val WheelRepeatMillis = 90L

/** The block views' dp paddings resolved once per density, so measurement stays pure arithmetic. */
internal class DesktopNovelMetrics(density: Density, settings: DesktopNovelSettings) {
    val paragraphGap: Int = with(density) { (settings.fontSize * .7f).dp.roundToPx() }
    val headingTop: Int = with(density) { 12.dp.roundToPx() }
    val headingBottom: Int = with(density) { 16.dp.roundToPx() }
    /** A divider plus its 16dp vertical padding. */
    val ruleHeight: Int = with(density) { 33.dp.roundToPx() }
}

/**
 * Height of every block at [widthPx]. A paragraph is measured as the very string and style the page shows; an
 * illustration takes a whole page, because its decoded size is unknown until it is drawn.
 */
internal fun desktopNovelBlockHeights(
    blocks: List<NovelBlock>,
    measurer: TextMeasurer,
    styles: DesktopNovelTextStyles,
    widthPx: Int,
    pageHeightPx: Int,
    metrics: DesktopNovelMetrics,
): List<Int> = blocks.map { block ->
    when (block) {
        is NovelBlock.Paragraph -> measurer.height(block.annotatedText(), styles.body, widthPx) + metrics.paragraphGap
        is NovelBlock.Heading -> measurer.height(AnnotatedString(block.text), styles.heading(block.level), widthPx) +
            metrics.headingTop + metrics.headingBottom
        NovelBlock.Rule -> metrics.ruleHeight
        is NovelBlock.Image -> pageHeightPx
    }
}

private fun TextMeasurer.height(text: AnnotatedString, style: TextStyle, widthPx: Int): Int =
    measure(text = text, style = style, constraints = Constraints(maxWidth = widthPx)).size.height

/** An illustration may not spill into the chrome: it is clamped to the page it owns. */
internal fun desktopNovelImageHeight(pageHeight: Dp): Dp = (pageHeight - VerticalPadding).coerceAtLeast(1.dp)
