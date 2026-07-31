package org.skepsun.kototoro.core.ui.compose

import android.view.View
import androidx.appcompat.R as appcompatR
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getThemeColor
import kotlin.math.floor

private const val SCROLLBAR_HIDE_DELAY_MS = 1000L

private val FastScrollTouchWidth = 32.dp
private val FastScrollInsetEnd = 24.dp
private val FastScrollHandleEndPadding = 0.dp

@Composable
fun BoxScope.VerticalScrollbar(
	state: LazyListState,
	modifier: Modifier = Modifier,
	width: Dp = dimensionResource(R.dimen.fastscroll_handle_width),
	color: Color = Color.Unspecified,
	trackColor: Color = Color.Transparent,
	draggable: Boolean = true,
	alwaysVisible: Boolean = false,
	endInset: Dp = FastScrollInsetEnd,
	labelProvider: ((Int) -> String)? = null,
) {
	FastScrollbar(
		modifier = modifier,
		width = width,
		color = color,
		trackColor = trackColor,
		draggable = draggable,
		alwaysVisible = alwaysVisible,
		endInset = endInset,
		labelProvider = labelProvider,
		totalItemsCount = { state.layoutInfo.totalItemsCount },
		visibleItemsCount = { state.layoutInfo.visibleItemsInfo.size },
		scrollFraction = { state.smoothListScrollFraction() },
		isScrollInProgress = { state.isScrollInProgress },
		requestScrollToItem = { index -> state.requestFastScrollToItem(index) },
	)
}

@Composable
fun BoxScope.VerticalScrollbar(
	state: LazyGridState,
	modifier: Modifier = Modifier,
	width: Dp = dimensionResource(R.dimen.fastscroll_handle_width),
	color: Color = Color.Unspecified,
	trackColor: Color = Color.Transparent,
	draggable: Boolean = true,
	alwaysVisible: Boolean = false,
	endInset: Dp = FastScrollInsetEnd,
	labelProvider: ((Int) -> String)? = null,
) {
	FastScrollbar(
		modifier = modifier,
		width = width,
		color = color,
		trackColor = trackColor,
		draggable = draggable,
		alwaysVisible = alwaysVisible,
		endInset = endInset,
		labelProvider = labelProvider,
		totalItemsCount = { state.layoutInfo.totalItemsCount },
		visibleItemsCount = { state.layoutInfo.visibleItemsInfo.size },
		scrollFraction = { state.smoothGridScrollFraction() },
		isScrollInProgress = { state.isScrollInProgress },
		requestScrollToItem = { index -> state.requestFastScrollToItem(index) },
	)
}

@Composable
private fun BoxScope.FastScrollbar(
	modifier: Modifier,
	width: Dp,
	color: Color,
	trackColor: Color,
	draggable: Boolean,
	alwaysVisible: Boolean,
	endInset: Dp,
	labelProvider: ((Int) -> String)?,
	totalItemsCount: () -> Int,
	visibleItemsCount: () -> Int,
	scrollFraction: () -> Float,
	isScrollInProgress: () -> Boolean,
	requestScrollToItem: (Int) -> Unit,
) {
	val context = LocalContext.current
	val rootView = LocalView.current
	val scrollTargets = remember { Channel<Int>(Channel.CONFLATED) }
	val showScrollbar by remember {
		derivedStateOf {
			val totalItems = totalItemsCount()
			totalItems > 0 && totalItems > visibleItemsCount()
		}
	}

	var isDragging by remember { mutableStateOf(false) }
	var dragFraction by remember { mutableFloatStateOf(0f) }
	var keepVisible by remember { mutableStateOf(false) }
	var lastDragTarget by remember { mutableIntStateOf(-1) }
	var trackHeightPx by remember { mutableFloatStateOf(0f) }
	var bubbleText by remember { mutableStateOf<String?>(null) }
	val scrolling = isScrollInProgress()

	DisposableEffect(scrollTargets) {
		onDispose {
			scrollTargets.close()
		}
	}

	LaunchedEffect(scrollTargets) {
		while (true) {
			val firstTarget = scrollTargets.receiveCatching().getOrNull() ?: break
			withFrameNanos { }
			var target = firstTarget
			while (true) {
				target = scrollTargets.tryReceive().getOrNull() ?: break
			}
			requestScrollToItem(target)
		}
	}

	LaunchedEffect(scrolling, isDragging, showScrollbar) {
		if ((scrolling || isDragging) && showScrollbar) {
			keepVisible = true
		} else {
			delay(SCROLLBAR_HIDE_DELAY_MS)
			keepVisible = false
		}
	}

	val alpha = if (showScrollbar && (alwaysVisible || keepVisible)) 1f else 0f
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
	val handleWidthPx = with(density) { width.toPx() }
	val handleHeightPx = with(density) { handleHeight.toPx() }
	val handleRadiusPx = with(density) { handleRadius.toPx() }
	val handleEndPaddingPx = with(density) { FastScrollHandleEndPadding.toPx() }

	Box(
		modifier = modifier
			.align(Alignment.CenterEnd)
			.fillMaxHeight()
			.padding(
				top = scrollbarMarginTop,
				end = endInset,
				bottom = scrollbarMarginBottom,
			)
			.width(FastScrollTouchWidth)
			.then(
				if (draggable) {
					Modifier.fastScrollbarPointerInput(
						rootView = rootView,
						showScrollbar = { showScrollbar },
						totalItemsCount = totalItemsCount,
						visibleItemsCount = visibleItemsCount,
						handleHeightPx = handleHeightPx,
						onDragStart = {
							isDragging = true
							keepVisible = true
							lastDragTarget = -1
						},
						onDragStop = {
							isDragging = false
						},
						onDragChanged = { position, index ->
							dragFraction = position
							if (index != lastDragTarget) {
								lastDragTarget = index
								bubbleText = labelProvider?.invoke(index)
								scrollTargets.trySend(index)
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
				drawRoundRect(
					color = handleColor.copy(alpha = handleColor.alpha * alpha),
					topLeft = Offset(barLeft, barTopPx),
					size = Size(handleWidthPx, barHeightPx),
					cornerRadius = CornerRadius(handleRadiusPx),
				)
			}

			if (isDragging && !bubbleText.isNullOrEmpty() && alpha > 0f && showScrollbar) {
				FastScrollBubble(
					text = bubbleText.orEmpty(),
					barTopPx = barTopPx,
					barHeightPx = barHeightPx,
					trackHeightPx = trackHeightPx,
					color = handleColor,
					endInset = endInset,
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
	endInset: Dp,
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
			.padding(end = endInset + FastScrollTouchWidth)
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
	totalItemsCount: () -> Int,
	visibleItemsCount: () -> Int,
	handleHeightPx: Float,
	onDragStart: () -> Unit,
	onDragStop: () -> Unit,
	onDragChanged: (position: Float, targetIndex: Int) -> Unit,
): Modifier = pointerInput(rootView, handleHeightPx) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		if (!showScrollbar()) {
			return@awaitEachGesture
		}

		rootView.parent?.requestDisallowInterceptTouchEvent(true)
		onDragStart()
		down.consume()

		fun scrollToPointer(y: Float) {
			val totalItems = totalItemsCount()
			val visibleItems = visibleItemsCount()
			if (totalItems <= 0 || totalItems <= visibleItems) {
				return
			}

			val availableHeight = (size.height - handleHeightPx).coerceAtLeast(1f)
			val fraction = (y - handleHeightPx / 2f).coerceIn(0f, availableHeight) / availableHeight
			val maxTargetIndex = (totalItems - 1).coerceAtLeast(1)
			val targetIndex = floor(fraction * maxTargetIndex).toInt().coerceIn(0, totalItems - 1)
			onDragChanged(fraction, targetIndex)
		}

		try {
			scrollToPointer(down.position.y)
			while (true) {
				val event = awaitPointerEvent()
				val change = event.changes.firstOrNull { it.id == down.id } ?: break
				if (!change.pressed) {
					break
				}
				change.consume()
				scrollToPointer(change.position.y)
			}
		} finally {
			onDragStop()
			rootView.parent?.requestDisallowInterceptTouchEvent(false)
		}
	}
}

private fun LazyListState.requestFastScrollToItem(index: Int) {
	val layoutInfo = layoutInfo
	val visibleItems = layoutInfo.visibleItemsInfo
	if (visibleItems.isEmpty()) {
		requestScrollToItem(index)
		return
	}
	val firstVisible = visibleItems.first().index
	val lastVisible = visibleItems.last().index
	val lastIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
	when {
		index >= lastIndex -> requestScrollToItem(lastIndex, Int.MAX_VALUE)
		index < firstVisible -> requestScrollToItem(index)
		index > lastVisible -> requestScrollToItem((index - visibleItems.size + 1).coerceAtLeast(0))
	}
}

private fun LazyGridState.requestFastScrollToItem(index: Int) {
	val layoutInfo = layoutInfo
	val visibleItems = layoutInfo.visibleItemsInfo
	if (visibleItems.isEmpty()) {
		requestScrollToItem(index)
		return
	}
	val firstVisible = visibleItems.minOf { it.index }
	val lastVisible = visibleItems.maxOf { it.index }
	val lastIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
	when {
		index >= lastIndex -> requestScrollToItem(lastIndex, Int.MAX_VALUE)
		index < firstVisible -> requestScrollToItem(index)
		index > lastVisible -> {
			val firstLineVisibleCount = visibleItems.minOfOrNull { it.offset.y }
				?.let { firstLineTop -> visibleItems.count { it.offset.y == firstLineTop } }
				?.coerceAtLeast(1)
				?: 1
			val visibleLineCount = visibleItems.map { it.offset.y }.distinct().size.coerceAtLeast(1)
			val estimatedVisibleCount = firstLineVisibleCount * visibleLineCount
			requestScrollToItem((index - estimatedVisibleCount + 1).coerceAtLeast(0))
		}
	}
}

private fun LazyListState.smoothListScrollFraction(): Float {
	val layoutInfo = layoutInfo
	val visibleItems = layoutInfo.visibleItemsInfo
	if (visibleItems.isEmpty()) {
		return 0f
	}
	val totalItems = layoutInfo.totalItemsCount
	if (totalItems <= visibleItems.size) {
		return 0f
	}
	val averageItemSize = visibleItems.map { it.size }.average().takeIf { it > 0.0 }?.toFloat() ?: return 0f
	val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1).toFloat()
	val totalScrollableDistance = (totalItems * averageItemSize - viewportSize).coerceAtLeast(1f)
	val scrolledDistance = firstVisibleItemIndex * averageItemSize + firstVisibleItemScrollOffset
	return (scrolledDistance / totalScrollableDistance).coerceIn(0f, 1f)
}

private fun LazyGridState.smoothGridScrollFraction(): Float {
	val layoutInfo = layoutInfo
	val visibleItems = layoutInfo.visibleItemsInfo
	if (visibleItems.isEmpty()) {
		return 0f
	}
	val totalItems = layoutInfo.totalItemsCount
	if (totalItems <= visibleItems.size) {
		return 0f
	}
	val firstLineTop = visibleItems.minOf { it.offset.y }
	val columns = visibleItems.count { it.offset.y == firstLineTop }.coerceAtLeast(1)
	val averageLineHeight = visibleItems
		.groupBy { it.offset.y }
		.values
		.map { line -> line.maxOf { it.size.height } }
		.average()
		.takeIf { it > 0.0 }
		?.toFloat() ?: return 0f
	val totalLines = (totalItems + columns - 1) / columns
	val firstVisibleLine = firstVisibleItemIndex / columns
	val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1).toFloat()
	val totalScrollableDistance = (totalLines * averageLineHeight - viewportSize).coerceAtLeast(1f)
	val scrolledDistance = firstVisibleLine * averageLineHeight + firstVisibleItemScrollOffset
	return (scrolledDistance / totalScrollableDistance).coerceIn(0f, 1f)
}
