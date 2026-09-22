package org.skepsun.kototoro.core.ui.compose

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.R as appcompatR
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getThemeColor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

private const val SCROLLBAR_HIDE_DELAY_MS = 1000L

/**
 * Width of the scrollbar's drag strip, which owns every touch landing inside it. Trailing-edge
 * controls on scrollable content have to stay clear of this strip or their taps become drags.
 */
internal val FastScrollTouchWidth = 36.dp
private val FastScrollInsetEnd = 2.dp

/**
 * CompositionLocal indicating whether a scrollbar is actively visible or being dragged.
 * Used by parent chrome and overlay elements (e.g. Space switcher handle) to dynamically avoid overlapping.
 */
val LocalScrollbarActive = compositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

@Immutable
data class ScrollbarTimelineSection(
    val label: String,
    val firstItemIndex: Int,
    val itemCount: Int,
)

@Immutable
data class ScrollbarTimeline(
    val sections: List<ScrollbarTimelineSection>,
)

private data class ScrollbarTimelineMarker(
    val position: Float,
    val intensity: Float,
)

@Composable
fun BoxScope.VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    width: Dp = dimensionResource(R.dimen.fastscroll_handle_width),
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Transparent,
    draggable: Boolean = true,
    alwaysVisible: Boolean = false,
    endInset: Dp = FastScrollInsetEnd,
    labelProvider: ((Int) -> String)? = null,
    timeline: ScrollbarTimeline? = null,
) {
    val estimator = remember { ScrollbarSectionEstimator() }
    val timelineEstimator = remember { TimelineScrollbarEstimator() }
    FastScrollbar(
        modifier = modifier,
        contentPadding = contentPadding,
        width = width,
        color = color,
        trackColor = trackColor,
        draggable = draggable,
        alwaysVisible = alwaysVisible,
        endInset = endInset,
        labelProvider = labelProvider,
        timelineLabelProvider = timeline?.let { currentTimeline ->
            { fraction -> timelineEstimator.labelForFraction(fraction, currentTimeline) }
        },
        timelineSectionProvider = timeline?.let { currentTimeline ->
            { fraction -> timelineEstimator.sectionForFraction(fraction, currentTimeline) }
        },
        timelineMarkerProvider = timeline?.let { currentTimeline ->
            { timelineEstimator.markers(currentTimeline) }
        },
        totalItemsCount = { state.layoutInfo.totalItemsCount },
        visibleItemsCount = { state.layoutInfo.visibleItemsInfo.size },
        scrollFraction = {
            if (timeline == null) {
                estimator.computeFraction(state.layoutInfo)
            } else {
                timelineEstimator.computeFraction(state.layoutInfo, timeline)
            }
        },
        isScrollInProgress = { state.isScrollInProgress },
        onFastScrollToFraction = { fraction ->
            val layoutInfo = state.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 0) return@FastScrollbar
            if (timeline != null) {
                val (targetIndex, targetOffset) = timelineEstimator.findItemAndOffsetForFraction(
                    fraction = fraction,
                    layoutInfo = layoutInfo,
                    timeline = timeline,
                )
                state.requestScrollToItem(targetIndex, targetOffset)
                return@FastScrollbar
            }
            if (fraction <= 0.001f) {
                state.requestScrollToItem(0, 0)
                return@FastScrollbar
            }
            if (fraction >= 0.999f) {
                state.requestScrollToItem(totalItems - 1, Int.MAX_VALUE)
                return@FastScrollbar
            }
            val (targetIndex, targetOffset) = estimator.findItemAndOffsetForFraction(fraction, layoutInfo)
            state.requestScrollToItem(targetIndex, targetOffset)
        },
    )
}

@Composable
fun BoxScope.VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    width: Dp = dimensionResource(R.dimen.fastscroll_handle_width),
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Transparent,
    draggable: Boolean = true,
    alwaysVisible: Boolean = false,
    endInset: Dp = FastScrollInsetEnd,
    labelProvider: ((Int) -> String)? = null,
) {
    val estimator = remember { GridScrollbarEstimator() }
    FastScrollbar(
        modifier = modifier,
        contentPadding = contentPadding,
        width = width,
        color = color,
        trackColor = trackColor,
        draggable = draggable,
        alwaysVisible = alwaysVisible,
        endInset = endInset,
        labelProvider = labelProvider,
        timelineLabelProvider = null,
        timelineSectionProvider = null,
        timelineMarkerProvider = null,
        totalItemsCount = { state.layoutInfo.totalItemsCount },
        visibleItemsCount = { state.layoutInfo.visibleItemsInfo.size },
        scrollFraction = { estimator.computeFraction(state) },
        isScrollInProgress = { state.isScrollInProgress },
        onFastScrollToFraction = { fraction ->
            val layoutInfo = state.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 0) return@FastScrollbar
            if (fraction <= 0.001f) {
                state.requestScrollToItem(0, 0)
                return@FastScrollbar
            }
            if (fraction >= 0.999f) {
                state.requestScrollToItem(totalItems - 1, Int.MAX_VALUE)
                return@FastScrollbar
            }
            val (_, targetOffset, targetIndex) = estimator.findRowAndOffsetForFraction(fraction, layoutInfo)
            state.requestScrollToItem(targetIndex, targetOffset)
        },
    )
}

@Composable
private fun BoxScope.FastScrollbar(
    modifier: Modifier,
    contentPadding: PaddingValues,
    width: Dp,
    color: Color,
    trackColor: Color,
    draggable: Boolean,
    alwaysVisible: Boolean,
    endInset: Dp,
    labelProvider: ((Int) -> String)?,
    timelineLabelProvider: ((Float) -> String?)?,
    timelineSectionProvider: ((Float) -> Int)?,
    timelineMarkerProvider: (() -> List<ScrollbarTimelineMarker>)?,
    totalItemsCount: () -> Int,
    visibleItemsCount: () -> Int,
    scrollFraction: () -> Float,
    isScrollInProgress: () -> Boolean,
    onFastScrollToFraction: (Float) -> Unit,
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val localScrollbarActive = LocalScrollbarActive.current
    val showScrollbar by remember {
        derivedStateOf {
            val totalItems = totalItemsCount()
            totalItems > 0 && totalItems > visibleItemsCount()
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var keepVisible by remember { mutableStateOf(false) }
    var lastBubbleIndex by remember { mutableIntStateOf(-1) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var bubbleText by remember { mutableStateOf<String?>(null) }
    val scrolling = isScrollInProgress()

    LaunchedEffect(scrolling, isDragging, showScrollbar) {
        if ((scrolling || isDragging) && showScrollbar) {
            keepVisible = true
        } else {
            delay(SCROLLBAR_HIDE_DELAY_MS)
            keepVisible = false
        }
    }

    val isActive = showScrollbar && (alwaysVisible || keepVisible || isDragging)
    DisposableEffect(isActive) {
        localScrollbarActive.value = isActive
        onDispose {
            localScrollbarActive.value = false
        }
    }

    val targetAlpha = if (isActive) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = if (targetAlpha > 0f) 150 else 300),
        label = "ScrollbarAlpha",
    )

    val density = LocalDensity.current
    val handleColor = remember(context, color) {
        if (color == Color.Unspecified) {
            Color(context.getThemeColor(appcompatR.attr.colorControlNormal, android.graphics.Color.DKGRAY))
        } else {
            color
        }
    }
    val handleHeight = dimensionResource(R.dimen.fastscroll_handle_height)
    val handleRadius = dimensionResource(R.dimen.fastscroll_handle_radius)
    val scrollbarMarginTop = dimensionResource(R.dimen.fastscroll_scrollbar_margin_top)
    val scrollbarMarginBottom = dimensionResource(R.dimen.fastscroll_scrollbar_margin_bottom)
    val layoutDirection = LocalLayoutDirection.current
    val effectiveTopPadding = contentPadding.calculateTopPadding() + scrollbarMarginTop
    val effectiveBottomPadding = contentPadding.calculateBottomPadding() + scrollbarMarginBottom
    val effectiveEndPadding = contentPadding.calculateEndPadding(layoutDirection)
    val handleWidthPx = with(density) { width.toPx() }
    val handleHeightPx = with(density) { handleHeight.toPx() }
    val handleRadiusPx = with(density) { handleRadius.toPx() }
    val handleEndPaddingPx = with(density) { endInset.toPx() }

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(
                top = effectiveTopPadding,
                bottom = effectiveBottomPadding,
                end = effectiveEndPadding,
            )
            .width(FastScrollTouchWidth)
            .then(
                if (showScrollbar && (alwaysVisible || keepVisible)) {
                    Modifier.systemGestureExclusion()
                } else {
                    Modifier
                },
            )
            .then(
                if (draggable) {
                    Modifier.fastScrollbarPointerInput(
                        rootView = rootView,
                        showScrollbar = { showScrollbar },
                        handleHeightPx = handleHeightPx,
                        onDragStart = {
                            isDragging = true
                            keepVisible = true
                            lastBubbleIndex = -1
                        },
                        onDragStop = {
                            isDragging = false
                            bubbleText = null
                        },
                        onDragFraction = { fraction ->
                            dragFraction = fraction
                            onFastScrollToFraction(fraction)
                            if (timelineLabelProvider != null) {
                                val sectionIndex = timelineSectionProvider?.invoke(fraction) ?: -1
                                if (sectionIndex != lastBubbleIndex) {
                                    if (lastBubbleIndex >= 0) {
                                        rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    lastBubbleIndex = sectionIndex
                                }
                                val newLabel = timelineLabelProvider(fraction)
                                if (newLabel != bubbleText) {
                                    bubbleText = newLabel
                                }
                            } else if (labelProvider != null) {
                                val total = totalItemsCount()
                                if (total > 0) {
                                    val targetIndex = (fraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
                                    if (targetIndex != lastBubbleIndex) {
                                        lastBubbleIndex = targetIndex
                                        val newLabel = labelProvider(targetIndex)
                                        if (newLabel != bubbleText) {
                                            bubbleText = newLabel
                                            rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }
                                }
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> trackHeightPx = size.height.toFloat() },
        ) {
            val totalItems = totalItemsCount()
            val visibleItems = visibleItemsCount()
            val currentScrollFraction = if (isDragging) {
                dragFraction
            } else {
                scrollFraction()
            }
            val barHeightPx = handleHeightPx.coerceAtMost(trackHeightPx)
            val barTopPx = (trackHeightPx - barHeightPx) * currentScrollFraction.coerceIn(0f, 1f)
            val timelineLabel = if (scrolling && !isDragging) {
                timelineLabelProvider?.invoke(currentScrollFraction)
            } else {
                null
            }
            val visibleBubbleText = if (isDragging) bubbleText else timelineLabel

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (alpha <= 0f || !showScrollbar || totalItems <= 0 || visibleItems <= 0 || totalItems <= visibleItems) {
                    return@Canvas
                }

                val barLeft = size.width - handleEndPaddingPx - handleWidthPx
                val trackWidthPx = (handleWidthPx / 3f).coerceAtLeast(1f)
                drawRoundRect(
                    color = trackColor.copy(alpha = trackColor.alpha * alpha),
                    topLeft = Offset(
                        x = barLeft + (handleWidthPx - trackWidthPx) / 2f,
                        y = 0f,
                    ),
                    size = Size(trackWidthPx, size.height),
                        cornerRadius = CornerRadius(trackWidthPx / 2f),
                    )
                timelineMarkerProvider?.invoke()?.forEach { marker ->
                    val markerY = (size.height - barHeightPx) * marker.position + barHeightPx / 2f
                    val markerRadius = (trackWidthPx * (0.55f + marker.intensity * 0.55f))
                        .coerceIn(1.5f, 3.5f)
                    drawCircle(
                        color = handleColor.copy(alpha = 0.58f * alpha),
                        radius = markerRadius,
                        center = Offset(
                            x = barLeft + handleWidthPx / 2f,
                            y = markerY,
                        ),
                    )
                }
                drawRoundRect(
                    color = handleColor.copy(alpha = handleColor.alpha * alpha),
                    topLeft = Offset(barLeft, barTopPx),
                    size = Size(handleWidthPx, barHeightPx),
                    cornerRadius = CornerRadius(handleRadiusPx),
                )
            }

            if ((isDragging || scrolling) && !visibleBubbleText.isNullOrEmpty() && alpha > 0f && showScrollbar) {
                FastScrollBubble(
                    text = visibleBubbleText.orEmpty(),
                    barTopPx = barTopPx,
                    barHeightPx = barHeightPx,
                    trackHeightPx = trackHeightPx,
                    color = handleColor,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun FastScrollBubble(
    text: String,
    barTopPx: Float,
    barHeightPx: Float,
    trackHeightPx: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bubbleSize = dimensionResource(R.dimen.fastscroll_bubble_size_small)
    val bubblePadding = dimensionResource(R.dimen.fastscroll_bubble_padding_small)
    val bubbleSizePx = with(density) { bubbleSize.toPx() }
    val bubbleTopPx = (barTopPx + barHeightPx / 2f - bubbleSizePx / 2f)
        .coerceIn(0f, (trackHeightPx - bubbleSizePx).coerceAtLeast(0f))
    Box(
        modifier = modifier
            .wrapContentSize(unbounded = true, align = Alignment.TopEnd)
            .padding(end = FastScrollTouchWidth + 8.dp)
            .padding(top = with(density) { bubbleTopPx.toDp() })
            .requiredSizeIn(minWidth = bubbleSize, minHeight = bubbleSize)
            .heightIn(min = bubbleSize)
            .background(color = color, shape = MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = bubblePadding),
        )
    }
}

private fun Modifier.fastScrollbarPointerInput(
    rootView: View,
    showScrollbar: () -> Boolean,
    handleHeightPx: Float,
    onDragStart: () -> Unit,
    onDragStop: () -> Unit,
    onDragFraction: (fraction: Float) -> Unit,
): Modifier = pointerInput(rootView, handleHeightPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!showScrollbar()) {
            return@awaitEachGesture
        }

        rootView.parent?.requestDisallowInterceptTouchEvent(true)
        onDragStart()
        down.consume()

        fun updateFraction(y: Float) {
            val availableHeight = (size.height - handleHeightPx).coerceAtLeast(1f)
            val fraction = (y - handleHeightPx / 2f).coerceIn(0f, availableHeight) / availableHeight
            onDragFraction(fraction)
        }

        try {
            updateFraction(down.position.y)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    break
                }
                change.consume()
                updateFraction(change.position.y)
            }
        } finally {
            onDragStop()
            rootView.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }
}

internal class ItemHeightTracker(initialCapacity: Int = 128) {
    private var capacity = initialCapacity
    private var treeHeight = LongArray(capacity + 1)
    private var treeCount = IntArray(capacity + 1)
    private var heights = IntArray(capacity)

    var totalKnownCount: Int = 0
        private set
    var totalKnownHeight: Long = 0L
        private set

    fun reset(expectedCapacity: Int = 128) {
        val newCap = maxOf(expectedCapacity, 16)
        capacity = newCap
        treeHeight = LongArray(newCap + 1)
        treeCount = IntArray(newCap + 1)
        heights = IntArray(newCap)
        totalKnownCount = 0
        totalKnownHeight = 0L
    }

    fun ensureCapacity(requiredSize: Int) {
        if (requiredSize > capacity) {
            val newCap = maxOf(requiredSize, capacity * 2)
            val oldHeights = heights
            val newHeights = IntArray(newCap)
            System.arraycopy(oldHeights, 0, newHeights, 0, minOf(oldHeights.size, newCap))
            heights = newHeights

            val newTreeHeight = LongArray(newCap + 1)
            val newTreeCount = IntArray(newCap + 1)
            for (i in oldHeights.indices) {
                val h = oldHeights[i]
                if (h > 0) {
                    addLong(newTreeHeight, i + 1, h.toLong(), newCap)
                    addInt(newTreeCount, i + 1, 1, newCap)
                }
            }
            treeHeight = newTreeHeight
            treeCount = newTreeCount
            capacity = newCap
        }
    }

    fun setHeight(index: Int, height: Int) {
        if (index < 0 || height <= 0) return
        ensureCapacity(index + 1)
        val oldHeight = heights[index]
        if (oldHeight != height) {
            val delta = (height - oldHeight).toLong()
            heights[index] = height
            addLong(treeHeight, index + 1, delta, capacity)
            if (oldHeight == 0) {
                totalKnownCount++
                addInt(treeCount, index + 1, 1, capacity)
            }
            totalKnownHeight += delta
        }
    }

    fun getHeight(index: Int): Int {
        if (index < 0 || index >= capacity) return 0
        return heights[index]
    }

    fun getKnownHeightBefore(untilIndex: Int): Long {
        if (untilIndex <= 0) return 0L
        return queryLong(treeHeight, minOf(untilIndex, capacity))
    }

    fun getKnownCountBefore(untilIndex: Int): Int {
        if (untilIndex <= 0) return 0
        return queryInt(treeCount, minOf(untilIndex, capacity))
    }

    private fun addLong(tree: LongArray, index: Int, delta: Long, cap: Int) {
        var idx = index
        while (idx <= cap) {
            tree[idx] += delta
            idx += idx and -idx
        }
    }

    private fun addInt(tree: IntArray, index: Int, delta: Int, cap: Int) {
        var idx = index
        while (idx <= cap) {
            tree[idx] += delta
            idx += idx and -idx
        }
    }

    private fun queryLong(tree: LongArray, index: Int): Long {
        var sum = 0L
        var idx = index
        while (idx > 0) {
            sum += tree[idx]
            idx -= idx and -idx
        }
        return sum
    }

    private fun queryInt(tree: IntArray, index: Int): Int {
        var sum = 0
        var idx = index
        while (idx > 0) {
            sum += tree[idx]
            idx -= idx and -idx
        }
        return sum
    }
}

internal class ScrollbarSectionEstimator {
    private val tracker = ItemHeightTracker()
    private var lastTotalItems = 0
    private var smoothedTotalHeight = 0f

    fun computeFraction(layoutInfo: LazyListLayoutInfo): Float {
        val visibleItems = layoutInfo.visibleItemsInfo
        val totalItems = layoutInfo.totalItemsCount
        if (visibleItems.isEmpty() || totalItems <= visibleItems.size) {
            return 0f
        }
        if (totalItems != lastTotalItems) {
            lastTotalItems = totalItems
            tracker.reset(totalItems)
            smoothedTotalHeight = 0f
        }

        for (item in visibleItems) {
            tracker.setHeight(item.index, item.size)
        }

        val topItem = visibleItems.first()
        val bottomItem = visibleItems.last()
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()

        val avgItemHeight = if (tracker.totalKnownCount > 0) {
            tracker.totalKnownHeight.toFloat() / tracker.totalKnownCount
        } else {
            visibleItems.map { it.size }.average().takeIf { it > 0 }?.toFloat() ?: 100f
        }

        val knownBefore = tracker.getKnownHeightBefore(topItem.index)
        val countBefore = tracker.getKnownCountBefore(topItem.index)
        val unknownBefore = (topItem.index - countBefore).coerceAtLeast(0)
        val scrolledPx = (knownBefore + unknownBefore * avgItemHeight - topItem.offset).coerceAtLeast(0f)

        val unknownTotal = (totalItems - tracker.totalKnownCount).coerceAtLeast(0)
        val estimatedTotalHeight = tracker.totalKnownHeight + unknownTotal * avgItemHeight
        val maxScrollPx = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)

        smoothedTotalHeight = if (smoothedTotalHeight <= 0f) {
            maxScrollPx
        } else {
            smoothedTotalHeight * 0.95f + maxScrollPx * 0.05f
        }

        if (topItem.index == 0 && topItem.offset >= 0) {
            return 0f
        }
        if (bottomItem.index == totalItems - 1) {
            val bottomOffset = (bottomItem.offset + bottomItem.size).toFloat()
            if (bottomOffset <= viewportHeight) {
                return 1f
            }
        }

        return (scrolledPx / smoothedTotalHeight).coerceIn(0f, 1f)
    }

    fun getTotalScrollRange(layoutInfo: LazyListLayoutInfo): Float {
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
        return if (smoothedTotalHeight > 0f) smoothedTotalHeight else viewportHeight
    }

    fun findItemAndOffsetForFraction(fraction: Float, layoutInfo: LazyListLayoutInfo): Pair<Int, Int> {
        val totalItems = layoutInfo.totalItemsCount
        if (totalItems <= 0) return 0 to 0
        if (fraction <= 0.001f) return 0 to 0
        if (fraction >= 0.999f) return (totalItems - 1) to Int.MAX_VALUE

        val maxScrollPx = getTotalScrollRange(layoutInfo)
        val targetPx = (fraction * maxScrollPx).coerceAtLeast(0f)

        val avgItemHeight = if (tracker.totalKnownCount > 0) {
            tracker.totalKnownHeight.toFloat() / tracker.totalKnownCount
        } else {
            100f
        }

        var low = 0
        var high = totalItems - 1
        var bestIndex = 0
        var bestOffset = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val known = tracker.getKnownHeightBefore(mid)
            val count = tracker.getKnownCountBefore(mid)
            val unknown = (mid - count).coerceAtLeast(0)
            val startOfMid = known + unknown * avgItemHeight

            val midHeight = tracker.getHeight(mid).takeIf { it > 0 } ?: avgItemHeight.toInt().coerceAtLeast(1)
            val endOfMid = startOfMid + midHeight

            if (targetPx < startOfMid) {
                high = mid - 1
            } else if (targetPx >= endOfMid) {
                low = mid + 1
            } else {
                bestIndex = mid
                bestOffset = (targetPx - startOfMid).toInt().coerceAtLeast(0)
                break
            }
            bestIndex = mid
            bestOffset = (targetPx - startOfMid).toInt().coerceAtLeast(0)
        }

        return bestIndex.coerceIn(0, totalItems - 1) to bestOffset
    }

    fun getMaxSections(): Float {
        val avg = if (tracker.totalKnownCount > 0) {
            tracker.totalKnownHeight.toFloat() / tracker.totalKnownCount
        } else {
            100f
        }
        return (smoothedTotalHeight / avg.coerceAtLeast(1f)).coerceAtLeast(1f)
    }
}

private const val TIMELINE_PREFIX_WEIGHT = 0.65f
private const val TIMELINE_ITEM_WEIGHT_FACTOR = 0.35f

private class TimelineScrollbarEstimator {

    fun computeFraction(
        layoutInfo: LazyListLayoutInfo,
        timeline: ScrollbarTimeline,
    ): Float {
        val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.size > 0 } ?: return 0f
        val metrics = createMetrics(timeline, layoutInfo.totalItemsCount)
        if (metrics.sections.isEmpty()) return 0f

        val itemProgress = ((layoutInfo.viewportStartOffset - visibleItem.offset).toFloat() / visibleItem.size)
            .coerceIn(0f, 1f)
        return metrics.positionFor(visibleItem.index + itemProgress)
    }

    fun findItemAndOffsetForFraction(
        fraction: Float,
        layoutInfo: LazyListLayoutInfo,
        timeline: ScrollbarTimeline,
    ): Pair<Int, Int> {
        val totalItems = layoutInfo.totalItemsCount
        if (totalItems <= 0) return 0 to 0

        val metrics = createMetrics(timeline, totalItems)
        if (metrics.sections.isEmpty()) return 0 to 0
        if (fraction <= 0.001f) return 0 to 0
        if (fraction >= 0.999f) return (totalItems - 1) to Int.MAX_VALUE

        val targetWeight = fraction.coerceIn(0f, 1f) * metrics.totalWeight
        if (metrics.prefixItemCount > 0 && targetWeight < metrics.prefixWeight) {
            val prefixProgress = targetWeight / metrics.prefixWeight
            val targetIndex = (prefixProgress * metrics.prefixItemCount)
                .toInt()
                .coerceIn(0, metrics.prefixItemCount - 1)
            return targetIndex to 0
        }

        metrics.sections.forEachIndexed { index, section ->
            val sectionStartWeight = metrics.sectionStartWeights[index]
            val sectionWeight = metrics.sectionWeights[index]
            val isLastSection = index == metrics.sections.lastIndex
            if (targetWeight <= sectionStartWeight + sectionWeight || isLastSection) {
                val sectionProgress = ((targetWeight - sectionStartWeight) / sectionWeight)
                    .coerceIn(0f, 1f)
                val targetIndex = section.firstItemIndex +
                    (sectionProgress * section.itemCount).toInt()
                return targetIndex.coerceIn(
                    section.firstItemIndex,
                    section.firstItemIndex + section.itemCount - 1,
                ) to 0
            }
        }

        return (totalItems - 1) to 0
    }

    fun labelForFraction(
        fraction: Float,
        timeline: ScrollbarTimeline,
    ): String? {
        val metrics = createMetrics(timeline, inferredTotalItems(timeline))
        if (metrics.sections.isEmpty()) return null

        val targetWeight = fraction.coerceIn(0f, 1f) * metrics.totalWeight
        if (metrics.prefixItemCount > 0 && targetWeight < metrics.prefixWeight) {
            return metrics.sections.first().label
        }

        metrics.sections.forEachIndexed { index, section ->
            val sectionEnd = metrics.sectionStartWeights[index] + metrics.sectionWeights[index]
            if (targetWeight <= sectionEnd || index == metrics.sections.lastIndex) {
                return section.label
            }
        }
        return metrics.sections.last().label
    }

    fun sectionForFraction(
        fraction: Float,
        timeline: ScrollbarTimeline,
    ): Int {
        val metrics = createMetrics(timeline, inferredTotalItems(timeline))
        if (metrics.sections.isEmpty()) return -1

        val targetWeight = fraction.coerceIn(0f, 1f) * metrics.totalWeight
        metrics.sections.forEachIndexed { index, _ ->
            val sectionEnd = metrics.sectionStartWeights[index] + metrics.sectionWeights[index]
            if (targetWeight <= sectionEnd || index == metrics.sections.lastIndex) {
                return index
            }
        }
        return metrics.sections.lastIndex
    }

    fun markers(timeline: ScrollbarTimeline): List<ScrollbarTimelineMarker> {
        val metrics = createMetrics(timeline, inferredTotalItems(timeline))
        if (metrics.sections.isEmpty()) return emptyList()

        val maxItemCount = metrics.sections.maxOf { it.itemCount }.coerceAtLeast(1)
        val maxLogCount = ln((maxItemCount + 1).toDouble()).toFloat()
        return metrics.sections.mapIndexed { index, section ->
            val logCount = ln((section.itemCount.coerceAtLeast(1) + 1).toDouble()).toFloat()
            ScrollbarTimelineMarker(
                position = (metrics.sectionStartWeights[index] / metrics.totalWeight).coerceIn(0f, 1f),
                intensity = (logCount / maxLogCount).coerceIn(0f, 1f),
            )
        }
    }

    private fun createMetrics(
        timeline: ScrollbarTimeline,
        totalItems: Int,
    ): TimelineMetrics {
        val sections = timeline.sections
            .filter { it.firstItemIndex >= 0 && it.itemCount > 0 }
            .sortedBy { it.firstItemIndex }
        if (sections.isEmpty()) return TimelineMetrics.empty()

        val firstItemIndex = sections.first().firstItemIndex.coerceAtMost(totalItems.coerceAtLeast(0))
        val prefixWeight = if (firstItemIndex > 0) TIMELINE_PREFIX_WEIGHT else 0f
        val sectionStartWeights = FloatArray(sections.size)
        val sectionWeights = FloatArray(sections.size)
        var totalWeight = prefixWeight
        sections.forEachIndexed { index, section ->
            sectionStartWeights[index] = totalWeight
            val weight = 1f + TIMELINE_ITEM_WEIGHT_FACTOR * ln((section.itemCount + 1).toDouble()).toFloat()
            sectionWeights[index] = weight
            totalWeight += weight
        }
        return TimelineMetrics(
            sections = sections,
            prefixItemCount = firstItemIndex,
            prefixWeight = prefixWeight,
            sectionStartWeights = sectionStartWeights,
            sectionWeights = sectionWeights,
            totalWeight = totalWeight.coerceAtLeast(1f),
        )
    }

    private fun inferredTotalItems(timeline: ScrollbarTimeline): Int {
        return timeline.sections.maxOfOrNull { it.firstItemIndex + it.itemCount }
            ?.coerceAtLeast(1)
            ?: 1
    }
}

private data class TimelineMetrics(
    val sections: List<ScrollbarTimelineSection>,
    val prefixItemCount: Int,
    val prefixWeight: Float,
    val sectionStartWeights: FloatArray,
    val sectionWeights: FloatArray,
    val totalWeight: Float,
) {
    fun positionFor(coordinate: Float): Float {
        if (sections.isEmpty()) return 0f
        if (prefixItemCount > 0 && coordinate < prefixItemCount) {
            return (coordinate.coerceAtLeast(0f) / prefixItemCount * prefixWeight) / totalWeight
        }

        sections.forEachIndexed { index, section ->
            val sectionStart = section.firstItemIndex.toFloat()
            val sectionEnd = sectionStart + section.itemCount
            val nextSectionStart = sections.getOrNull(index + 1)?.firstItemIndex?.toFloat()
            if (coordinate < sectionEnd || coordinate < (nextSectionStart ?: Float.POSITIVE_INFINITY) || index == sections.lastIndex) {
                val sectionProgress = ((coordinate - sectionStart) / section.itemCount)
                    .coerceIn(0f, 1f)
                return (sectionStartWeights[index] + sectionWeights[index] * sectionProgress) / totalWeight
            }
        }
        return 1f
    }

    companion object {
        fun empty() = TimelineMetrics(
            sections = emptyList(),
            prefixItemCount = 0,
            prefixWeight = 0f,
            sectionStartWeights = FloatArray(0),
            sectionWeights = FloatArray(0),
            totalWeight = 1f,
        )
    }
}

internal class GridScrollbarEstimator {
    private val tracker = ItemHeightTracker()
    private var lastKnownColumns = 1
    private var lastTotalItems = 0
    private var smoothedTotalHeight = 0f

    fun computeFraction(state: LazyGridState): Float = computeFraction(state.layoutInfo)

    fun computeFraction(layoutInfo: androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo): Float {
        val visibleItems = layoutInfo.visibleItemsInfo
        val totalItems = layoutInfo.totalItemsCount
        if (visibleItems.isEmpty() || totalItems <= visibleItems.size) {
            return 0f
        }
        if (totalItems != lastTotalItems) {
            lastTotalItems = totalItems
            tracker.reset(totalItems)
            smoothedTotalHeight = 0f
        }

        val rowsInView = visibleItems.groupBy { it.row }
        for ((row, items) in rowsInView) {
            val rowHeight = items.maxOf { it.size.height }
            tracker.setHeight(row, rowHeight)
        }

        val maxColsInView = rowsInView.maxOfOrNull { it.value.size } ?: 1
        if (maxColsInView > lastKnownColumns) {
            lastKnownColumns = maxColsInView
        }

        val startRow = visibleItems.minOf { it.row }
        val startRowOffset = visibleItems.filter { it.row == startRow }.minOf { it.offset.y }
        val endRow = visibleItems.maxOf { it.row }
        val endRowBottom = visibleItems.filter { it.row == endRow }.maxOf { it.offset.y + it.size.height }
        val lastVisibleItemIndex = visibleItems.maxOf { it.index }
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()

        val avgRowHeight = if (tracker.totalKnownCount > 0) {
            tracker.totalKnownHeight.toFloat() / tracker.totalKnownCount
        } else {
            rowsInView.values.map { it.maxOf { item -> item.size.height } }.average().takeIf { it > 0 }?.toFloat() ?: 200f
        }

        val knownBefore = tracker.getKnownHeightBefore(startRow)
        val countBefore = tracker.getKnownCountBefore(startRow)
        val unknownBefore = (startRow - countBefore).coerceAtLeast(0)
        val scrolledPx = (knownBefore + unknownBefore * avgRowHeight - startRowOffset).coerceAtLeast(0f)

        val columnCount = maxOf(lastKnownColumns, 1)
        val remainingItems = (totalItems - 1 - lastVisibleItemIndex).coerceAtLeast(0)
        val remainingRows = kotlin.math.ceil(remainingItems.toFloat() / columnCount).toInt()
        val totalEstimatedRows = maxOf(endRow + 1 + remainingRows, tracker.totalKnownCount)

        val unknownTotalRows = (totalEstimatedRows - tracker.totalKnownCount).coerceAtLeast(0)
        val estimatedTotalHeight = tracker.totalKnownHeight + unknownTotalRows * avgRowHeight
        val maxScrollPx = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)

        smoothedTotalHeight = if (smoothedTotalHeight <= 0f) {
            maxScrollPx
        } else {
            smoothedTotalHeight * 0.95f + maxScrollPx * 0.05f
        }

        if (startRow == 0 && startRowOffset >= 0) {
            return 0f
        }
        if (lastVisibleItemIndex == totalItems - 1 && endRowBottom <= viewportHeight) {
            return 1f
        }

        return (scrolledPx / smoothedTotalHeight).coerceIn(0f, 1f)
    }

    fun getTotalScrollRange(layoutInfo: androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo): Float {
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
        return if (smoothedTotalHeight > 0f) smoothedTotalHeight else viewportHeight
    }

    fun findRowAndOffsetForFraction(fraction: Float, layoutInfo: androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo): Triple<Int, Int, Int> {
        val totalItems = layoutInfo.totalItemsCount
        if (totalItems <= 0) return Triple(0, 0, 0)
        if (fraction <= 0.001f) return Triple(0, 0, 0)
        if (fraction >= 0.999f) return Triple(Int.MAX_VALUE, Int.MAX_VALUE, totalItems - 1)

        val maxScrollPx = getTotalScrollRange(layoutInfo)
        val targetPx = (fraction * maxScrollPx).coerceAtLeast(0f)

        val avgRowHeight = if (tracker.totalKnownCount > 0) {
            tracker.totalKnownHeight.toFloat() / tracker.totalKnownCount
        } else {
            200f
        }

        val columnCount = maxOf(lastKnownColumns, 1)
        val totalEstimatedRows = maxOf(tracker.totalKnownCount, (totalItems + columnCount - 1) / columnCount)

        var low = 0
        var high = totalEstimatedRows - 1
        var bestRow = 0
        var bestOffset = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val known = tracker.getKnownHeightBefore(mid)
            val count = tracker.getKnownCountBefore(mid)
            val unknown = (mid - count).coerceAtLeast(0)
            val startOfMid = known + unknown * avgRowHeight

            val midHeight = tracker.getHeight(mid).takeIf { it > 0 } ?: avgRowHeight.toInt().coerceAtLeast(1)
            val endOfMid = startOfMid + midHeight

            if (targetPx < startOfMid) {
                high = mid - 1
            } else if (targetPx >= endOfMid) {
                low = mid + 1
            } else {
                bestRow = mid
                bestOffset = (targetPx - startOfMid).toInt().coerceAtLeast(0)
                break
            }
            bestRow = mid
            bestOffset = (targetPx - startOfMid).toInt().coerceAtLeast(0)
        }

        val targetIndex = (bestRow * columnCount).coerceIn(0, totalItems - 1)
        return Triple(bestRow, bestOffset, targetIndex)
    }

    fun getEstimatedTotalRows(): Float {
        val avg = if (tracker.totalKnownCount > 0) {
            tracker.totalKnownHeight.toFloat() / tracker.totalKnownCount
        } else {
            200f
        }
        return (smoothedTotalHeight / avg.coerceAtLeast(1f)).coerceAtLeast(1f)
    }

    fun getColumnCount(): Int = lastKnownColumns.coerceAtLeast(1)
}
