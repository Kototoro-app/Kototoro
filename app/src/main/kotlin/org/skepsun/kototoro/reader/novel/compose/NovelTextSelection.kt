package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingColor
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingStyle
import kotlin.math.abs

enum class NovelTextSelectionAction {
    COPY,
    SHARE,
    DICTIONARY,
    BOOKMARK,
    HIGHLIGHT,
    NOTE,
    EXCERPT,
    ASK_AI,
    LISTEN,
    DELETE,
}

data class NovelTextSelection(
    val text: String,
    val chapterId: Long,
    val chapterIndex: Int,
    val chapterText: String,
    val renderedStart: Int? = null,
    val renderedText: String? = null,
    val selectionAnchor: Offset? = null,
    val anchorRect: androidx.compose.ui.geometry.Rect? = null,
    val sessionId: Long = 0L,
    val ownerId: String = "",
    val activeMarkingId: Long? = null,
    val clear: () -> Unit,
) {
    val renderedRange: IntRange?
        get() {
            val start = renderedStart ?: return null
            val rendered = renderedText ?: return null
            val localStart = rendered.indexOf(text)
            if (localStart < 0) return null
            return (start + localStart) until (start + localStart + text.length)
        }
}

internal class NovelSelectionTextToolbar(
    private val onShow: (androidx.compose.ui.geometry.Rect) -> Unit,
    private val onHide: () -> Unit,
) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        status = TextToolbarStatus.Shown
        onShow(rect)
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        onHide()
    }
}

/** Adds selection reporting while retaining Compose's native handles without a second action bar. */
@Composable
internal fun NovelSelectionContainer(
    chapterId: Long,
    chapterIndex: Int,
    chapterText: String,
    onSelectionChanged: (NovelTextSelection?) -> Unit,
    ownerId: String = "novel-chapter-$chapterId-$chapterIndex",
    renderedStart: Int? = null,
    renderedText: String? = null,
    content: @Composable () -> Unit,
) {
    val selectionState = rememberSelectionState()
    var menuAnchorRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val textToolbar = remember {
        NovelSelectionTextToolbar(
            onShow = { rect ->
                menuAnchorRect = rect
            },
            onHide = {
                menuAnchorRect = null
            },
        )
    }
    val selectedText = remember(selectionState.selectedTexts) {
        selectionState.selectedTexts
            .joinToString(separator = "\n", transform = AnnotatedString::text)
            .trim()
    }
    LaunchedEffect(selectedText, chapterId, chapterIndex, chapterText, menuAnchorRect, ownerId) {
        if (selectedText.isBlank()) {
            menuAnchorRect = null
            onSelectionChanged(null)
        } else {
            val anchor = menuAnchorRect?.let { Offset(it.left + it.width / 2f, it.top) }
            onSelectionChanged(
                NovelTextSelection(
                    text = selectedText,
                    chapterId = chapterId,
                    chapterIndex = chapterIndex,
                    chapterText = chapterText,
                    renderedStart = renderedStart,
                    renderedText = renderedText,
                    selectionAnchor = anchor,
                    anchorRect = menuAnchorRect,
                    ownerId = ownerId,
                    clear = selectionState::clear,
                ),
            )
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        SelectionContainer(
            state = selectionState,
            content = content,
        )
    }
}

@Composable
internal fun NovelMarkingText(
    text: AnnotatedString,
    style: TextStyle,
    textAlign: TextAlign,
    sourceRange: IntRange?,
    markings: List<NovelMarkingEntity>,
    onMarkingClick: (NovelMarkingEntity, androidx.compose.ui.geometry.Rect) -> Unit,
    textSelectionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentLayoutResult by rememberUpdatedState(layoutResult)
    val clickModifier = if (markings.isEmpty()) {
        Modifier
    } else {
        Modifier.pointerInput(text, sourceRange, markings, textSelectionActive, onMarkingClick) {
            detectNovelMarkingTap(
                layoutResult = { currentLayoutResult },
                resolveMarking = { position ->
                    currentLayoutResult?.let { layout ->
                        findNovelMarkingAtOffset(
                            textOffset = layout.getOffsetForPosition(position),
                            text = text.text,
                            sourceRange = sourceRange,
                            markings = markings,
                        )
                    }
                },
                selectionActive = textSelectionActive,
                onMarkingClick = { marking, releasePosition ->
                    val layout = currentLayoutResult
                    val coords = textCoordinates
                    val rootRect = if (layout != null && coords != null && coords.isAttached) {
                        val localRange = resolveNovelMarkingRenderedRange(text.text, sourceRange, marking)
                        val localStart = localRange?.first ?: 0
                        val localEnd = localRange?.let { it.last + 1 } ?: 0
                        val localBounds = if (localEnd > localStart) {
                            // Anchor to the whole visible marking fragment. The release line is
                            // deliberately ignored so a tap in the middle of a wrapped marking
                            // cannot move the panel over the remaining text.
                            runCatching { layout.getPathForRange(localStart, localEnd).getBounds() }
                                .getOrNull()
                                ?.takeIf { it.width > 0f && it.height > 0f }
                                ?: layout.getBoundingBox(localStart.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
                        } else {
                            val charIndex = localStart.coerceIn(0, (text.length - 1).coerceAtLeast(0))
                            if (text.isNotEmpty()) layout.getBoundingBox(charIndex)
                            else androidx.compose.ui.geometry.Rect(
                                releasePosition,
                                androidx.compose.ui.geometry.Size(1f, 1f),
                            )
                        }
                        val rootTopLeft = coords.localToRoot(localBounds.topLeft)
                        val rootBottomRight = coords.localToRoot(localBounds.bottomRight)
                        androidx.compose.ui.geometry.Rect(rootTopLeft, rootBottomRight)
                    } else {
                        androidx.compose.ui.geometry.Rect(releasePosition, androidx.compose.ui.geometry.Size(1f, 1f))
                    }
                    onMarkingClick(marking, rootRect)
                },
            )
        }
    }
    androidx.compose.material3.Text(
        text = text,
        style = style,
        textAlign = textAlign,
        modifier = modifier
            .onGloballyPositioned { textCoordinates = it }
            .then(clickModifier)
            .drawBehind {
                val layout = layoutResult ?: return@drawBehind
                if (markings.isEmpty()) return@drawBehind
                markings.forEach { marking ->
                    val markingColor = NovelMarkingColor.fromId(marking.color)
                    val markingStyle = NovelMarkingStyle.fromId(marking.style)

                    val localRange = resolveNovelMarkingRenderedRange(text.text, sourceRange, marking)
                    val (localStart, localEnd) = if (localRange != null) {
                        localRange.first to (localRange.last + 1)
                    } else {
                        return@forEach
                    }

                    if (localEnd <= localStart) return@forEach

                    val firstLine = layout.getLineForOffset(localStart)
                    val lastLine = layout.getLineForOffset((localEnd - 1).coerceAtLeast(localStart))
                    for (line in firstLine..lastLine) {
                        val lineStart = layout.getLineStart(line)
                        val lineEnd = layout.getLineEnd(line, visibleEnd = true)
                        val segStart = maxOf(localStart, lineStart)
                        val segEnd = minOf(localEnd, lineEnd)
                        if (segEnd <= segStart) continue

                        val x1 = if (segStart <= lineStart) {
                            layout.getLineLeft(line)
                        } else {
                            layout.getHorizontalPosition(segStart, usePrimaryDirection = true)
                        }
                        val x2 = if (segEnd >= lineEnd) {
                            layout.getLineRight(line)
                        } else {
                            layout.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                        }
                        val left = minOf(x1, x2)
                        val right = maxOf(x1, x2)
                        if (right <= left) continue

                        val lineBottom = layout.getLineBottom(line)
                        val lineTop = layout.getLineTop(line)

                        when (markingStyle) {
                            NovelMarkingStyle.HIGHLIGHT -> {
                                val paddingH = 2.dp.toPx()
                                val topInset = 1.dp.toPx()
                                val bottomInset = 1.dp.toPx()
                                drawRoundRect(
                                    color = markingColor.bgColor,
                                    topLeft = Offset(left - paddingH, lineTop + topInset),
                                    size = Size(
                                        right - left + paddingH * 2,
                                        lineBottom - lineTop - topInset - bottomInset,
                                    ),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                )
                            }
                            NovelMarkingStyle.UNDERLINE -> {
                                val strokeWidth = 2.dp.toPx()
                                val y = lineBottom - 2.dp.toPx()
                                drawLine(
                                    color = markingColor.lineColor,
                                    start = Offset(left, y),
                                    end = Offset(right, y),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Round,
                                )
                            }
                            NovelMarkingStyle.WAVY -> {
                                val yBase = lineBottom - 3.dp.toPx()
                                val waveLength = 7.dp.toPx()
                                val waveHeight = 2.dp.toPx()
                                val path = Path()
                                path.moveTo(left, yBase)
                                var curX = left
                                while (curX < right) {
                                    val nextX = minOf(curX + waveLength, right)
                                    val segW = nextX - curX
                                    val cp1x = curX + segW * 0.25f
                                    val cp1y = yBase - waveHeight
                                    val cp2x = curX + segW * 0.75f
                                    val cp2y = yBase + waveHeight
                                    path.cubicTo(cp1x, cp1y, cp2x, cp2y, nextX, yBase)
                                    curX = nextX
                                }
                                drawPath(
                                    path = path,
                                    color = markingColor.lineColor,
                                    style = Stroke(
                                        width = 1.8.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            .fillMaxWidth(),
        onTextLayout = { layoutResult = it },
    )
}

internal fun findNovelMarkingAtOffset(
    textOffset: Int,
    text: String,
    sourceRange: IntRange?,
    markings: List<NovelMarkingEntity>,
): NovelMarkingEntity? {
    return markings.firstOrNull { marking ->
        resolveNovelMarkingRenderedRange(text, sourceRange, marking)?.contains(textOffset) == true
    }
}

private fun resolveNovelMarkingRenderedRange(
    text: String,
    sourceRange: IntRange?,
    marking: NovelMarkingEntity,
): IntRange? {
    val sourceLocalRange = resolveNovelMarkingLocalRange(
        sourceRange = sourceRange,
        markingStart = marking.startOffset,
        markingEnd = marking.endOffset,
        textLength = text.length,
    )
    val textRange = findNovelTextRangeNear(
        text = text,
        selectedText = marking.selectedText,
        expectedStart = sourceLocalRange?.first,
    )
    return when {
        textRange != null && (sourceRange == null || sourceLocalRange != null) -> textRange
        else -> sourceLocalRange
    }
}

private fun findNovelTextRangeNear(
    text: String,
    selectedText: String,
    expectedStart: Int?,
): IntRange? = org.skepsun.kototoro.reader.novel.findNovelTextRangeNear(text, selectedText, expectedStart)

/**
 * Converts a persisted, half-open source range into the local range rendered by one text block.
 * A marking can cross page or paragraph boundaries, so every block must render its intersection.
 */
/** Shared with iOS (reader-novel). */
internal fun resolveNovelMarkingLocalRange(
    sourceRange: IntRange?,
    markingStart: Int,
    markingEnd: Int,
    textLength: Int,
): IntRange? = org.skepsun.kototoro.reader.novel.resolveNovelMarkingLocalRange(sourceRange, markingStart, markingEnd, textLength)

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectNovelMarkingTap(
    layoutResult: () -> TextLayoutResult?,
    resolveMarking: (Offset) -> NovelMarkingEntity?,
    selectionActive: Boolean,
    onMarkingClick: (NovelMarkingEntity, Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        var moved = false
        var up: Offset? = null
        var upChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.pressed) {
                if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop ||
                    abs(change.position.y - down.position.y) > viewConfiguration.touchSlop
                ) {
                    moved = true
                }
            } else {
                up = change.position
                upChange = change
            }
        } while (change.pressed)
        val releasePosition = up ?: return@awaitEachGesture
        if (!moved && !selectionActive && upChange?.isConsumed != true) {
            val marking = resolveMarking(releasePosition)
            if (marking != null && layoutResult() != null) {
                upChange?.consume()
                onMarkingClick(marking, releasePosition)
            }
        }
    }
}

/** Shared with iOS (reader-novel). */
internal fun findNovelTextRange(
    source: String,
    selected: String,
    preferredStart: Int = 0,
): IntRange? = org.skepsun.kototoro.reader.novel.findNovelTextRange(source, selected, preferredStart)
