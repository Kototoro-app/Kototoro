package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import kotlin.math.abs

internal data class ComposeReaderPageTransform(
	val translationFactor: Float = 0f,
	val alpha: Float = 1f,
	val rotationX: Float = 0f,
	val rotationY: Float = 0f,
	val transformOrigin: TransformOrigin = TransformOrigin.Center,
	val zIndex: Float = 0f,
	val foldProgress: Float = 0f,
	val revealedPageShade: Float = 0f,
)

internal fun resolveComposeReaderPageTransform(
	animation: ReaderAnimation,
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
): ComposeReaderPageTransform = when (animation) {
	ReaderAnimation.DEFAULT -> ComposeReaderPageTransform()
	ReaderAnimation.NONE -> ComposeReaderPageTransform(
		translationFactor = when {
			pageOffset in -0.5f..0.5f -> -pageOffset
			pageOffset > 0f -> 1f
			else -> -1f
		},
	)
	ReaderAnimation.ADVANCED -> resolveAdvancedPageTransform(pageOffset, isVertical, isReversed)
	ReaderAnimation.SIMULATION -> resolveSimulationPageTransform(pageOffset, isVertical, isReversed)
}

private fun resolveSimulationPageTransform(
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
): ComposeReaderPageTransform {
	val reversed = isReversed && !isVertical
	val isTurningPage = if (reversed) pageOffset >= 0f else pageOffset <= 0f
	val foldProgress = if (isTurningPage) abs(pageOffset).coerceIn(0f, 1f) else 0f
	val revealedPageShade = if (!isTurningPage && abs(pageOffset) <= 1f) {
		abs(pageOffset) * 0.16f
	} else {
		0f
	}
	return ComposeReaderPageTransform(
		translationFactor = -pageOffset,
		alpha = if (pageOffset !in -1f..1f) {
			0f
		} else {
			1f - ((foldProgress - 0.92f) / 0.08f).coerceIn(0f, 1f)
		},
		zIndex = if (isTurningPage) 1f else -abs(pageOffset),
		foldProgress = foldProgress,
		revealedPageShade = revealedPageShade,
	)
}

private fun resolveAdvancedPageTransform(
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
): ComposeReaderPageTransform {
	if (pageOffset !in -1f..1f) {
		return ComposeReaderPageTransform(
			translationFactor = -pageOffset,
			alpha = 0f,
		)
	}
	if (isVertical) {
		return ComposeReaderPageTransform(
			translationFactor = -pageOffset,
			rotationX = if (pageOffset <= 0f) -120f * pageOffset else 0f,
			transformOrigin = TransformOrigin(0.5f, 0.2f),
		)
	}
	return ComposeReaderPageTransform(
		translationFactor = -pageOffset,
		rotationY = when {
			isReversed && pageOffset > 0f -> 120f * pageOffset
			!isReversed && pageOffset <= 0f -> 120f * pageOffset
			else -> 0f
		},
		transformOrigin = TransformOrigin(if (isReversed) 1f else 0f, 0.5f),
	)
}

internal const val READER_PAGE_CAMERA_DISTANCE = 20_000f

@Stable
internal class ComposeReaderPageCurlState internal constructor() {
	var downFraction by mutableStateOf(Offset(0.75f, 0.85f))
		private set
	var touchFraction by mutableStateOf(Offset(0.75f, 0.85f))
		private set
	var horizontalDragDirection by mutableStateOf(0f)
		private set
	var isGestureInProgress by mutableStateOf(false)
		private set

	val horizontalDragFraction: Float
		get() = touchFraction.x - downFraction.x

	internal fun beginTouch(position: Offset, viewport: Size) {
		val fraction = position.toTouchFraction(viewport) ?: return
		downFraction = fraction
		touchFraction = fraction
		horizontalDragDirection = 0f
		isGestureInProgress = true
	}

	internal fun updateTouch(position: Offset, viewport: Size) {
		touchFraction = position.toTouchFraction(viewport) ?: return
		if (horizontalDragDirection == 0f) {
			val dragFraction = horizontalDragFraction
			if (abs(dragFraction) >= PAGE_CURL_DIRECTION_SLOP) {
				horizontalDragDirection = if (dragFraction > 0f) 1f else -1f
			}
		}
	}

	internal fun endTouch() {
		isGestureInProgress = false
	}

	private fun Offset.toTouchFraction(viewport: Size): Offset? {
		if (viewport.width <= 0f || viewport.height <= 0f) return null
		return Offset(
			x = (x / viewport.width).coerceIn(0f, 1f),
			y = (y / viewport.height).coerceIn(0f, 1f),
		)
	}
}

@Composable
internal fun rememberComposeReaderPageCurlState(): ComposeReaderPageCurlState =
	remember { ComposeReaderPageCurlState() }

internal fun Modifier.trackComposeReaderPageCurl(
	state: ComposeReaderPageCurlState,
	enabled: Boolean,
): Modifier = pointerInput(state, enabled) {
	if (!enabled) return@pointerInput
	awaitEachGesture {
		val down = awaitFirstDown(
			requireUnconsumed = false,
			pass = PointerEventPass.Initial,
		)
		val viewport = Size(size.width.toFloat(), size.height.toFloat())
		state.beginTouch(
			position = down.position,
			viewport = viewport,
		)
		try {
			do {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				event.changes.firstOrNull { it.pressed }?.let { change ->
					state.updateTouch(change.position, viewport)
				}
			} while (event.changes.any { it.pressed })
		} finally {
			state.endTouch()
		}
	}
}

internal fun Modifier.composeReaderPageCurl(
	transform: ComposeReaderPageTransform,
	isVertical: Boolean,
	isReversed: Boolean,
	state: ComposeReaderPageCurlState,
	horizontalTouchRange: ClosedFloatingPointRange<Float> = 0f..1f,
	followTouchDuringGesture: Boolean? = null,
): Modifier {
	if (transform.foldProgress <= 0f) return this
	val curlFromStart = resolvePageCurlFromStart(
		isVertical = isVertical,
		isReversed = isReversed,
		horizontalDragDirection = state.horizontalDragDirection,
	)
	val followTouch = shouldFollowPageCurlTouch(
		isVertical = isVertical,
		isReversed = isReversed,
		isGestureInProgress = state.isGestureInProgress,
		horizontalDragDirection = state.horizontalDragDirection,
		followTouchDuringGesture = followTouchDuringGesture,
	)
	val trackedTouchFraction = if (followTouch) state.touchFraction else state.downFraction
	val touchRangeWidth = horizontalTouchRange.endInclusive - horizontalTouchRange.start
	val touchFraction = Offset(
		x = if (touchRangeWidth > 0f) {
			((trackedTouchFraction.x - horizontalTouchRange.start) / touchRangeWidth).coerceIn(0f, 1f)
		} else {
			trackedTouchFraction.x
		},
		y = trackedTouchFraction.y,
	)
	return drawWithCache {
		val progress = transform.foldProgress.coerceIn(0f, 1f)
		val geometry = calculatePageCurlGeometry(
			size = size,
			progress = progress,
			touchFraction = touchFraction,
			isVertical = isVertical,
			isReversed = curlFromStart,
			followTouch = followTouch,
		)
		val foldPath = Path().apply {
			moveTo(geometry.start1.x, geometry.start1.y)
			quadraticTo(
				geometry.control1.x,
				geometry.control1.y,
				geometry.end1.x,
				geometry.end1.y,
			)
			lineTo(geometry.touch.x, geometry.touch.y)
			lineTo(geometry.end2.x, geometry.end2.y)
			quadraticTo(
				geometry.control2.x,
				geometry.control2.y,
				geometry.start2.x,
				geometry.start2.y,
			)
			lineTo(geometry.corner.x, geometry.corner.y)
			close()
		}
		val foldedBackPath = Path().apply {
			moveTo(geometry.vertex2.x, geometry.vertex2.y)
			lineTo(geometry.vertex1.x, geometry.vertex1.y)
			lineTo(geometry.end1.x, geometry.end1.y)
			lineTo(geometry.touch.x, geometry.touch.y)
			lineTo(geometry.end2.x, geometry.end2.y)
			close()
		}
		val foldLine = Path().apply {
			moveTo(geometry.start1.x, geometry.start1.y)
			quadraticTo(
				geometry.control1.x,
				geometry.control1.y,
				geometry.end1.x,
				geometry.end1.y,
			)
			lineTo(geometry.touch.x, geometry.touch.y)
			lineTo(geometry.end2.x, geometry.end2.y)
			quadraticTo(
				geometry.control2.x,
				geometry.control2.y,
				geometry.start2.x,
				geometry.start2.y,
			)
		}
		val foldMidpoint = Offset(
			x = (geometry.vertex1.x + geometry.vertex2.x) / 2f,
			y = (geometry.vertex1.y + geometry.vertex2.y) / 2f,
		)

		onDrawWithContent drawContent@{
			clipPath(foldPath, clipOp = ClipOp.Difference) { this@drawContent.drawContent() }
			clipPath(foldPath) {
				drawPath(
					path = foldedBackPath,
					brush = Brush.linearGradient(
						colors = listOf(
							Color(0xFFE7E7E7),
							Color(0xFF8A8A8A),
						),
						start = geometry.touch,
						end = foldMidpoint,
					),
				)
			}
			drawPath(
				path = foldLine,
				color = Color.Black.copy(alpha = 0.12f + progress * 0.18f),
				style = Stroke(width = 1.5f + progress * 5f),
			)
		}
	}
}

internal fun resolvePageCurlFromStart(
	isVertical: Boolean,
	isReversed: Boolean,
	horizontalDragDirection: Float,
): Boolean = when {
	isVertical -> isReversed
	horizontalDragDirection > 0f -> true
	horizontalDragDirection < 0f -> false
	else -> isReversed
}

internal fun shouldFollowPageCurlTouch(
	isVertical: Boolean,
	isReversed: Boolean,
	isGestureInProgress: Boolean,
	horizontalDragDirection: Float,
	followTouchDuringGesture: Boolean?,
): Boolean {
	if (isVertical || !isGestureInProgress || horizontalDragDirection == 0f) return false
	followTouchDuringGesture?.let { return it }
	val isBackwardGesture = if (isReversed) {
		horizontalDragDirection < 0f
	} else {
		horizontalDragDirection > 0f
	}
	return !isBackwardGesture
}

internal data class ComposeReaderPageCurlGeometry(
	val touch: Offset,
	val corner: Offset,
	val control1: Offset,
	val control2: Offset,
	val start1: Offset,
	val start2: Offset,
	val end1: Offset,
	val end2: Offset,
	val vertex1: Offset,
	val vertex2: Offset,
)

internal fun calculatePageCurlGeometry(
	size: Size,
	progress: Float,
	touchFraction: Offset,
	isVertical: Boolean,
	isReversed: Boolean,
	followTouch: Boolean = false,
): ComposeReaderPageCurlGeometry {
	val canonicalWidth = if (isVertical) size.height else size.width
	val canonicalHeight = if (isVertical) size.width else size.height
	val cornerOnStart = !isVertical && isReversed
	val cornerOnTop = if (isVertical) touchFraction.x < 0.5f else touchFraction.y < 0.5f
	val cornerX = if (cornerOnStart) 0f else canonicalWidth
	val cornerY = if (cornerOnTop) 0f else canonicalHeight
	val initialTouchX = if (isVertical) {
		touchFraction.y * canonicalWidth
	} else {
		touchFraction.x * canonicalWidth
	}.coerceIn(0.1f, canonicalWidth - 0.1f)
	val initialTouchY = if (isVertical) {
		touchFraction.x * canonicalHeight
	} else {
		touchFraction.y * canonicalHeight
	}.coerceIn(1f, canonicalHeight - 1f)
	val targetTouchX = if (cornerOnStart) canonicalWidth * 2f else -canonicalWidth
	val targetTouchY = if (cornerOnTop) 1f else canonicalHeight - 1f
	var touchX = if (followTouch) {
		initialTouchX
	} else {
		lerp(initialTouchX, targetTouchX, progress.coerceIn(0f, 1f))
	}
	var touchY = if (followTouch) {
		initialTouchY
	} else {
		lerp(initialTouchY, targetTouchY, progress.coerceIn(0f, 1f))
	}

	fun calculateControls(): Pair<Offset, Offset> {
		val middleX = (touchX + cornerX) / 2f
		val middleY = (touchY + cornerY) / 2f
		return Offset(
			x = middleX - (cornerY - middleY) * (cornerY - middleY) /
				safePageCurlDenominator(cornerX - middleX),
			y = cornerY,
		) to Offset(
			x = cornerX,
			y = middleY - (cornerX - middleX) * (cornerX - middleX) /
				safePageCurlDenominator(cornerY - middleY),
		)
	}

	var (control1, control2) = calculateControls()
	var start1 = Offset(control1.x - (cornerX - control1.x) / 2f, cornerY)
	if (touchX in 0f..canonicalWidth && start1.x !in 0f..canonicalWidth) {
		val adjustedStartX = if (start1.x < 0f) canonicalWidth - start1.x else start1.x
		val horizontalDistance = abs(cornerX - touchX).coerceAtLeast(0.1f)
		val constrainedDistance = canonicalWidth * horizontalDistance /
			safePageCurlDenominator(adjustedStartX)
		touchX = abs(cornerX - constrainedDistance).coerceIn(0.1f, canonicalWidth - 0.1f)
		val verticalDistance = abs(cornerX - touchX) * abs(cornerY - touchY) / horizontalDistance
		touchY = abs(cornerY - verticalDistance).coerceIn(1f, canonicalHeight - 1f)
		val controls = calculateControls()
		control1 = controls.first
		control2 = controls.second
		start1 = Offset(control1.x - (cornerX - control1.x) / 2f, cornerY)
	}
	val start2 = Offset(
		x = cornerX,
		y = control2.y - (cornerY - control2.y) / 2f,
	)
	val touch = Offset(touchX, touchY)
	val end1 = pageCurlLineIntersection(touch, control1, start1, start2)
	val end2 = pageCurlLineIntersection(touch, control2, start1, start2)
	val vertex1 = Offset(
		x = (start1.x + 2f * control1.x + end1.x) / 4f,
		y = (start1.y + 2f * control1.y + end1.y) / 4f,
	)
	val vertex2 = Offset(
		x = (start2.x + 2f * control2.x + end2.x) / 4f,
		y = (start2.y + 2f * control2.y + end2.y) / 4f,
	)
	fun map(point: Offset): Offset = if (isVertical) Offset(point.y, point.x) else point

	return ComposeReaderPageCurlGeometry(
		touch = map(touch),
		corner = map(Offset(cornerX, cornerY)),
		control1 = map(control1),
		control2 = map(control2),
		start1 = map(start1),
		start2 = map(start2),
		end1 = map(end1),
		end2 = map(end2),
		vertex1 = map(vertex1),
		vertex2 = map(vertex2),
	)
}

private fun pageCurlLineIntersection(
	line1Start: Offset,
	line1End: Offset,
	line2Start: Offset,
	line2End: Offset,
): Offset {
	val slope1 = (line1End.y - line1Start.y) /
		safePageCurlDenominator(line1End.x - line1Start.x)
	val intercept1 = (line1Start.x * line1End.y - line1End.x * line1Start.y) /
		safePageCurlDenominator(line1Start.x - line1End.x)
	val slope2 = (line2End.y - line2Start.y) /
		safePageCurlDenominator(line2End.x - line2Start.x)
	val intercept2 = (line2Start.x * line2End.y - line2End.x * line2Start.y) /
		safePageCurlDenominator(line2Start.x - line2End.x)
	val x = (intercept2 - intercept1) / safePageCurlDenominator(slope1 - slope2)
	return Offset(x, slope1 * x + intercept1)
}

private fun safePageCurlDenominator(value: Float): Float = when {
	abs(value) >= 0.1f -> value
	value < 0f -> -0.1f
	else -> 0.1f
}

private const val PAGE_CURL_DIRECTION_SLOP = 0.002f

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

@Composable
internal fun ComposeReaderSimulationPageShadow(
	transform: ComposeReaderPageTransform,
) {
	Canvas(modifier = Modifier.fillMaxSize()) {
		if (transform.revealedPageShade > 0f) {
			drawRect(Color.Black.copy(alpha = transform.revealedPageShade))
		}
	}
}
