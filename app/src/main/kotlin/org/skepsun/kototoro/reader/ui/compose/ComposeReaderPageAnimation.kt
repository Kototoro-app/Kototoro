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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import kotlin.math.abs
import kotlin.math.sin

internal data class ComposeReaderPageTransform(
	val translationFactor: Float = 0f,
	val alpha: Float = 1f,
	val rotationX: Float = 0f,
	val rotationY: Float = 0f,
	val scaleX: Float = 1f,
	val scaleY: Float = 1f,
	val transformOrigin: TransformOrigin = TransformOrigin.Center,
	val zIndex: Float = 0f,
	val foldProgress: Float = 0f,
	val revealedPageShade: Float = 0f,
	val bendProgress: Float = 0f,
	val bendPosition: Float = 1f,
	val bendWidth: Float = 0f,
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
			alpha = 0f,
		)
	}
	if (isVertical) {
		return ComposeReaderPageTransform(
			rotationX = if (pageOffset <= 0f) -120f * pageOffset else 0f,
			transformOrigin = TransformOrigin(0.5f, 0.2f),
		)
	}
	val isTurningPage = if (isReversed) pageOffset >= 0f else pageOffset <= 0f
	if (!isTurningPage) {
		return ComposeReaderPageTransform(
			translationFactor = -pageOffset,
			zIndex = -1f,
			revealedPageShade = abs(pageOffset) * 0.12f,
		)
	}
	val progress = abs(pageOffset).coerceIn(0f, 1f)
	val propagation = smoothStep((progress / ADVANCED_BEND_PROPAGATION_END).coerceIn(0f, 1f))
	return ComposeReaderPageTransform(
		rotationY = (if (isReversed) 12f else -12f) * propagation,
		scaleX = 1f - 0.04f * propagation,
		scaleY = 1f + 0.025f * propagation,
		transformOrigin = TransformOrigin(if (isReversed) 0f else 1f, 0.5f),
		zIndex = 1f,
		bendProgress = propagation,
		bendPosition = lerp(0.92f, 0.46f, propagation),
		bendWidth = lerp(0.28f, 1.12f, propagation),
	)
}

internal const val READER_PAGE_CAMERA_DISTANCE = 20_000f

@Stable
internal class ComposeReaderPageCurlState internal constructor() {
	var downFraction by mutableStateOf(Offset(0.75f, 0.85f))
		private set
	var touchFraction by mutableStateOf(Offset(0.75f, 0.85f))
		private set

	val horizontalDragFraction: Float
		get() = touchFraction.x - downFraction.x

	internal fun beginTouch(position: Offset, viewport: Size) {
		val fraction = position.toTouchFraction(viewport) ?: return
		downFraction = fraction
		touchFraction = fraction
	}

	internal fun updateTouch(position: Offset, viewport: Size) {
		touchFraction = position.toTouchFraction(viewport) ?: return
	}

	internal fun resetDrag() {
		touchFraction = downFraction
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
		do {
			val event = awaitPointerEvent(PointerEventPass.Initial)
			event.changes.firstOrNull { it.pressed }?.let { change ->
				state.updateTouch(change.position, viewport)
			}
		} while (event.changes.any { it.pressed })
	}
}

internal fun Modifier.composeReaderPageCurl(
	transform: ComposeReaderPageTransform,
	isVertical: Boolean,
	isReadingReversed: Boolean,
	state: ComposeReaderPageCurlState,
): Modifier {
	if (transform.foldProgress <= 0f) return this
	val curlFromStart = resolvePageCurlFromStart(
		isVertical = isVertical,
		isReadingReversed = isReadingReversed,
		horizontalDragFraction = state.horizontalDragFraction,
	)
	val touchFraction = resolvePageCurlStartFraction(
		downFraction = state.downFraction,
		isVertical = isVertical,
		curlFromStart = curlFromStart,
	)
	return drawWithCache {
		val progress = transform.foldProgress.coerceIn(0f, 1f)
		val geometry = calculatePageCurlGeometry(
			size = size,
			progress = progress,
			touchFraction = touchFraction,
			isVertical = isVertical,
			isReversed = curlFromStart,
			followTouch = false,
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
		val edgeShadowProgress = sin(progress * kotlin.math.PI).toFloat().coerceIn(0f, 1f)

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
			if (edgeShadowProgress > 0f) {
				drawPath(
					path = foldLine,
					color = Color.Black.copy(alpha = 0.08f * edgeShadowProgress),
					style = Stroke(width = 18f + 10f * edgeShadowProgress),
				)
				drawPath(
					path = foldLine,
					color = Color.Black.copy(alpha = 0.12f * edgeShadowProgress),
					style = Stroke(width = 7f + 5f * edgeShadowProgress),
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
	isReadingReversed: Boolean,
	horizontalDragFraction: Float,
): Boolean = when {
	isVertical -> isReadingReversed
	horizontalDragFraction > PAGE_CURL_DIRECTION_SLOP -> true
	horizontalDragFraction < -PAGE_CURL_DIRECTION_SLOP -> false
	else -> isReadingReversed
}

internal fun resolvePageCurlStartFraction(
	downFraction: Offset,
	isVertical: Boolean,
	curlFromStart: Boolean,
): Offset {
	if (isVertical) return downFraction
	return Offset(
		x = if (curlFromStart) 0f else 1f,
		y = if (downFraction.y < 0.5f) 0f else 1f,
	)
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
private const val ADVANCED_BEND_PROPAGATION_END = 0.64f

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

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

internal fun Modifier.composeReaderAdvancedPageEffect(
	transform: ComposeReaderPageTransform,
	isReversed: Boolean,
	imageSize: IntSize?,
	zoomMode: ZoomMode,
): Modifier {
	val hasTransform = transform.rotationX != 0f || transform.rotationY != 0f ||
		transform.scaleX != 1f || transform.scaleY != 1f
	if (!hasTransform && transform.bendProgress <= 0f && transform.revealedPageShade <= 0f) return this
	return graphicsLayer {
		val horizontalBounds = resolveAdvancedImageHorizontalBounds(
			viewport = Size(size.width.toFloat(), size.height.toFloat()),
			imageSize = imageSize,
			zoomMode = zoomMode,
		)
		alpha = transform.alpha
		rotationX = transform.rotationX
		rotationY = transform.rotationY
		scaleX = transform.scaleX
		scaleY = transform.scaleY
		transformOrigin = TransformOrigin(
			pivotFractionX = if (size.width > 0) {
				(if (isReversed) horizontalBounds.start else horizontalBounds.endInclusive) / size.width
			} else {
				transform.transformOrigin.pivotFractionX
			},
			pivotFractionY = transform.transformOrigin.pivotFractionY,
		)
		cameraDistance = READER_PAGE_CAMERA_DISTANCE
		compositingStrategy = CompositingStrategy.Offscreen
	}.drawWithCache {
		val horizontalBounds = resolveAdvancedImageHorizontalBounds(
			viewport = size,
			imageSize = imageSize,
			zoomMode = zoomMode,
		)
		val imageStart = horizontalBounds.start
		val imageEnd = horizontalBounds.endInclusive
		val imageWidth = imageEnd - imageStart
		onDrawWithContent {
			drawContent()
			if (transform.revealedPageShade > 0f) {
				drawRect(
					color = Color.Black.copy(alpha = transform.revealedPageShade),
					blendMode = BlendMode.SrcAtop,
				)
			}
			val bend = transform.bendProgress.coerceIn(0f, 1f)
			if (bend <= 0f) return@onDrawWithContent
			val bendCenterFraction = if (isReversed) 1f - transform.bendPosition else transform.bendPosition
			val bendStart = imageStart + imageWidth * (bendCenterFraction - transform.bendWidth / 2f)
			val bendEnd = imageStart + imageWidth * (bendCenterFraction + transform.bendWidth / 2f)
			drawRect(
				brush = Brush.horizontalGradient(
					colorStops = if (isReversed) {
						arrayOf(
							0f to Color.Transparent,
							0.24f to Color.Black.copy(alpha = 0.1f * bend),
							0.48f to Color.White.copy(alpha = 0.1f * bend),
							0.7f to Color.Black.copy(alpha = 0.13f * bend),
							1f to Color.Transparent,
						)
					} else {
						arrayOf(
							0f to Color.Transparent,
							0.3f to Color.Black.copy(alpha = 0.13f * bend),
							0.52f to Color.White.copy(alpha = 0.1f * bend),
							0.76f to Color.Black.copy(alpha = 0.1f * bend),
							1f to Color.Transparent,
						)
					},
					startX = bendStart,
					endX = bendEnd,
				),
				blendMode = BlendMode.SrcAtop,
			)
			drawRect(
				brush = Brush.horizontalGradient(
					colorStops = if (isReversed) {
						arrayOf(
							0f to Color.Black.copy(alpha = 0.2f * bend),
							0.08f to Color.Black.copy(alpha = 0.08f * bend),
							0.18f to Color.Transparent,
						)
					} else {
						arrayOf(
							0.82f to Color.Transparent,
							0.92f to Color.Black.copy(alpha = 0.08f * bend),
							1f to Color.Black.copy(alpha = 0.2f * bend),
						)
					},
					startX = imageStart,
					endX = imageEnd,
				),
				blendMode = BlendMode.SrcAtop,
			)
		}
	}
}

internal fun resolveAdvancedImageHorizontalBounds(
	viewport: Size,
	imageSize: IntSize?,
	zoomMode: ZoomMode,
): ClosedFloatingPointRange<Float> {
	if (viewport.width <= 0f || viewport.height <= 0f || imageSize == null ||
		imageSize.width <= 0 || imageSize.height <= 0
	) {
		return 0f..viewport.width
	}
	val imageWidth = imageSize.width.toFloat()
	val imageHeight = imageSize.height.toFloat()
	val scale = when (zoomMode) {
		ZoomMode.FIT_HEIGHT -> viewport.height / imageHeight
		ZoomMode.FIT_WIDTH -> viewport.width / imageWidth
		ZoomMode.FIT_CENTER,
		ZoomMode.KEEP_START -> minOf(viewport.width / imageWidth, viewport.height / imageHeight)
	}
	val displayedWidth = imageWidth * scale
	val start = (viewport.width - displayedWidth) / 2f
	return start..(start + displayedWidth)
}
