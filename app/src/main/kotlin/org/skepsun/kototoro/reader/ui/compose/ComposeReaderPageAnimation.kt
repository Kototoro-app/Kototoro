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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

internal data class ComposeReaderPageTransform(
	val translationFactor: Float = 0f,
	val alpha: Float = 1f,
	val rotationY: Float = 0f,
	val transformOrigin: TransformOrigin = TransformOrigin.Center,
	val zIndex: Float = 0f,
	val foldProgress: Float = 0f,
	val isCurlUnfolding: Boolean = false,
	val revealedPageShade: Float = 0f,
)

internal fun resolveComposeReaderPageTransform(
	animation: ReaderAnimation,
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
	navigationProgress: Float = 0f,
	isSettledPage: Boolean = false,
	isIncomingPage: Boolean = false,
	isCurlUnfolding: Boolean = false,
): ComposeReaderPageTransform = when (animation) {
	ReaderAnimation.DEFAULT -> ComposeReaderPageTransform()
	ReaderAnimation.NONE -> ComposeReaderPageTransform(
		translationFactor = when {
			pageOffset in -0.5f..0.5f -> -pageOffset
			pageOffset > 0f -> 1f
			else -> -1f
		},
	)
	ReaderAnimation.ADVANCED -> resolveCoverPageTransform(
		pageOffset = pageOffset,
		navigationProgress = navigationProgress,
		isSettledPage = isSettledPage,
		isIncomingPage = isIncomingPage,
		isReversed = isReversed,
	)
	ReaderAnimation.SIMULATION -> resolveSimulationPageTransform(
		pageOffset = pageOffset,
		isVertical = isVertical,
		isReversed = isReversed,
		isCurlUnfolding = isCurlUnfolding,
	)
}

private fun resolveCoverPageTransform(
	pageOffset: Float,
	navigationProgress: Float,
	isSettledPage: Boolean,
	isIncomingPage: Boolean,
	isReversed: Boolean,
): ComposeReaderPageTransform {
	if (abs(navigationProgress) < COVER_PAGE_EPSILON) {
		return ComposeReaderPageTransform()
	}

	val isForwardNavigation = navigationProgress > 0f
	val physicalDirection = if (isReversed) -1f else 1f
	return when {
		isSettledPage && isForwardNavigation -> ComposeReaderPageTransform(
			// The Pager's normal motion moves the current page left. The next
			// page is positioned underneath it by the adjacent-page branch below.
			zIndex = 1f,
		)
		isSettledPage -> ComposeReaderPageTransform(
			// On backward navigation the current page must remain fixed while
			// the previous page enters from the reading-direction edge above it.
			translationFactor = pageOffset * physicalDirection,
		)
		isIncomingPage && !isSettledPage -> ComposeReaderPageTransform(
			translationFactor = if (isForwardNavigation) {
				pageOffset * physicalDirection
			} else {
				0f
			},
			zIndex = if (isForwardNavigation) 0f else 1f,
		)
		else -> ComposeReaderPageTransform(zIndex = -1f)
	}
}

private const val COVER_PAGE_EPSILON = 0.001f

internal fun resolveAdvancedNavigationProgress(
	anchorPage: Int,
	currentPage: Int,
	currentPageOffsetFraction: Float,
): Float = ((currentPage - anchorPage) + currentPageOffsetFraction).coerceIn(-1f, 1f)

internal fun resolveAdvancedAnimationAnchor(
	anchorPage: Int,
	currentPage: Int,
	currentPageOffsetFraction: Float,
	isScrollInProgress: Boolean,
): Int {
	if (currentPage == anchorPage) return anchorPage
	if (abs(currentPageOffsetFraction) < COVER_PAGE_EPSILON) return currentPage
	if (!isScrollInProgress) return anchorPage

	val unboundedProgress = (currentPage - anchorPage) + currentPageOffsetFraction
	return if (abs(unboundedProgress) > 1f + COVER_PAGE_EPSILON) {
		// A new navigation started before the previous Pager animation became
		// idle. Commit the completed adjacent page as the next animation anchor.
		currentPage
	} else {
		anchorPage
	}
}

private fun resolveSimulationPageTransform(
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
	isCurlUnfolding: Boolean,
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
		// Keep the revealed spread below the turning spread, but above the
		// pager's own background so its image content remains drawable.
		zIndex = if (isTurningPage) 1f else 0f,
		foldProgress = foldProgress,
		isCurlUnfolding = isTurningPage && isCurlUnfolding,
		revealedPageShade = revealedPageShade,
	)
}

@Stable
internal class ComposeReaderPageCurlState internal constructor() {
	var downFraction by mutableStateOf(Offset(0.75f, 0.85f))
		private set
	var touchFraction by mutableStateOf(Offset(0.75f, 0.85f))
		private set

	val horizontalDragFraction: Float
		get() = touchFraction.x - downFraction.x
	val verticalDragFraction: Float
		get() = touchFraction.y - downFraction.y

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
		state.beginTouch(down.position, viewport)
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
	drawBackContent: Boolean = true,
): Modifier {
	if (transform.foldProgress <= 0f) return this
	val curlFromStart = resolvePageCurlFromStart(
		isVertical = isVertical,
		isReadingReversed = isReadingReversed,
		horizontalDragFraction = state.horizontalDragFraction,
	)
	return drawWithCache {
		val geometry = calculatePageCurlGeometry(
			size = size,
			progress = transform.foldProgress,
			touchFraction = resolvePageCurlStartFraction(
				downFraction = state.downFraction,
				isVertical = isVertical,
				curlFromStart = curlFromStart,
			),
			isVertical = isVertical,
			isReversed = curlFromStart,
			isCurlUnfolding = transform.isCurlUnfolding,
		)
		val frontPath = geometry.frontPath.toPath()
		val backPath = geometry.backPath.toPath()
		val shadowProgress = (1f - abs(geometry.curlLineVector.y) / size.height.coerceAtLeast(1f))
		val drawBackPage: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit = backPage@{
			withTransform({
				if (isVertical) {
					scale(1f, -1f, pivot = geometry.bottomCurlOffset)
					rotateRad(-geometry.angle, pivot = geometry.bottomCurlOffset)
				} else {
					scale(-1f, 1f, pivot = geometry.bottomCurlOffset)
					rotateRad(geometry.angle, pivot = geometry.bottomCurlOffset)
				}
			}) {
				drawPath(
					path = backPath,
					color = Color.Black.copy(alpha = 0.12f + shadowProgress.coerceIn(0f, 1f) * 0.12f),
					style = Stroke(width = 18f),
				)
				clipPath(backPath) {
					if (drawBackContent) this@backPage.drawContent()
					drawRect(Color.White.copy(alpha = 0.1f))
				}
			}
		}
		onDrawWithContent {
			clipPath(frontPath) {
				this@onDrawWithContent.drawContent()
			}
			drawBackPage()
		}
	}
}

internal fun resolvePageCurlFromStart(
	isVertical: Boolean,
	isReadingReversed: Boolean,
	horizontalDragFraction: Float,
): Boolean = isReadingReversed

internal fun resolvePageCurlUnfolding(
	settledPage: Int,
	targetPage: Int,
	horizontalDragFraction: Float,
	isReadingReversed: Boolean,
	verticalDragFraction: Float = 0f,
	isVertical: Boolean = false,
): Boolean = when {
	targetPage < settledPage -> true
	targetPage > settledPage -> false
	isVertical -> verticalDragFraction > PAGE_CURL_DRAG_EPSILON
	isReadingReversed -> horizontalDragFraction < -PAGE_CURL_DRAG_EPSILON
	else -> horizontalDragFraction > PAGE_CURL_DRAG_EPSILON
}

internal fun resolvePageCurlStartFraction(
	downFraction: Offset,
	isVertical: Boolean,
	curlFromStart: Boolean,
): Offset {
	if (isVertical) return downFraction
	return Offset(
		x = if (curlFromStart) 0f else 1f,
		y = downFraction.y,
	)
}

internal data class ComposeReaderPageCurlGeometry(
	val topCurlOffset: Offset,
	val bottomCurlOffset: Offset,
	val frontPath: List<Offset>,
	val backPath: List<Offset>,
	val angle: Float,
	val curlLineVector: Offset,
)

internal fun calculatePageCurlGeometry(
	size: Size,
	progress: Float,
	touchFraction: Offset,
	isVertical: Boolean,
	isReversed: Boolean,
	isCurlUnfolding: Boolean = false,
): ComposeReaderPageCurlGeometry {
	val canonicalWidth = if (isVertical) size.height else size.width
	val canonicalHeight = if (isVertical) size.width else size.height
	if (canonicalWidth <= 0f || canonicalHeight <= 0f) {
		return ComposeReaderPageCurlGeometry(
			topCurlOffset = Offset.Zero,
			bottomCurlOffset = Offset.Zero,
			frontPath = emptyList(),
			backPath = emptyList(),
			angle = 0f,
			curlLineVector = Offset.Zero,
		)
	}
	val edge = calculatePageCurlEdge(
		width = canonicalWidth,
		height = canonicalHeight,
		progress = progress,
		startFraction = (if (isVertical) touchFraction.x else touchFraction.y).let { dragOrigin ->
			if (isCurlUnfolding) 1f - dragOrigin else dragOrigin
		},
		isReversed = isReversed,
	)
	val topIntersection = lineLineIntersection(
		Offset(0f, 0f), Offset(canonicalWidth, 0f), edge.top, edge.bottom,
	) ?: edge.top
	val bottomIntersection = lineLineIntersection(
		Offset(0f, canonicalHeight), Offset(canonicalWidth, canonicalHeight), edge.top, edge.bottom,
	) ?: edge.bottom
	val topCurlOffset = if (isReversed) {
		Offset(min(canonicalWidth, topIntersection.x), 0f)
	} else {
		Offset(max(0f, topIntersection.x), 0f)
	}
	val bottomCurlOffset = if (isReversed) {
		Offset(min(canonicalWidth, bottomIntersection.x), canonicalHeight)
	} else {
		Offset(max(0f, bottomIntersection.x), canonicalHeight)
	}
	val frontPath = if (isReversed) {
		listOf(
			Offset(canonicalWidth, 0f),
			topCurlOffset,
			bottomCurlOffset,
			Offset(canonicalWidth, canonicalHeight),
		)
	} else {
		listOf(
			Offset.Zero,
			topCurlOffset,
			bottomCurlOffset,
			Offset(0f, canonicalHeight),
		)
	}
	val backPath = calculatePageCurlBackPath(
		width = canonicalWidth,
		height = canonicalHeight,
		topCurlOffset = topCurlOffset,
		bottomCurlOffset = bottomCurlOffset,
		isReversed = isReversed,
	)
	fun map(point: Offset): Offset = if (isVertical) Offset(point.y, point.x) else point
	val mappedTop = map(topCurlOffset)
	val mappedBottom = map(bottomCurlOffset)
	return ComposeReaderPageCurlGeometry(
		topCurlOffset = mappedTop,
		bottomCurlOffset = mappedBottom,
		frontPath = frontPath.map(::map),
		backPath = backPath.map(::map),
		angle = Math.PI.toFloat() - atan2(
			bottomCurlOffset.y - topCurlOffset.y,
			bottomCurlOffset.x - topCurlOffset.x,
		) * 2f,
		curlLineVector = map(bottomCurlOffset - topCurlOffset),
	)
}

private data class PageCurlEdge(val top: Offset, val bottom: Offset)

private fun calculatePageCurlEdge(
	width: Float,
	height: Float,
	progress: Float,
	startFraction: Float,
	isReversed: Boolean,
): PageCurlEdge {
	val start = PageCurlEdge(Offset(width, 0f), Offset(width, height))
	val middle = PageCurlEdge(Offset(width, height / 2f), Offset(width / 2f, height))
	val end = PageCurlEdge(Offset(0f, 0f), Offset(0f, height))
	val normalized = progress.coerceIn(0f, 1f)
	val bottomEdge = if (normalized <= 1f / 3f) {
		lerpEdge(start, middle, normalized * 3f)
	} else {
		lerpEdge(middle, end, (normalized - 1f / 3f) * 1.5f)
	}
	val topIntersection = lineLineIntersection(
		Offset.Zero,
		Offset(width, 0f),
		bottomEdge.top,
		bottomEdge.bottom,
	) ?: bottomEdge.top
	val bottomIntersection = lineLineIntersection(
		Offset(0f, height),
		Offset(width, height),
		bottomEdge.top,
		bottomEdge.bottom,
	) ?: bottomEdge.bottom
	val centerX = lerp(width, 0f, normalized)
	val cornerBias = startFraction.coerceIn(0f, 1f) * 2f - 1f
	val halfSlope = (topIntersection.x - bottomIntersection.x) / 2f * cornerBias
	val edge = PageCurlEdge(
		top = Offset(centerX + halfSlope, 0f),
		bottom = Offset(centerX - halfSlope, height),
	)
	return if (isReversed) {
		PageCurlEdge(
			top = Offset(width - edge.top.x, edge.top.y),
			bottom = Offset(width - edge.bottom.x, edge.bottom.y),
		)
	} else {
		edge
	}
}

private fun lerpEdge(start: PageCurlEdge, end: PageCurlEdge, fraction: Float): PageCurlEdge =
	PageCurlEdge(
		top = Offset(
			lerp(start.top.x, end.top.x, fraction),
			lerp(start.top.y, end.top.y, fraction),
		),
		bottom = Offset(
			lerp(start.bottom.x, end.bottom.x, fraction),
			lerp(start.bottom.y, end.bottom.y, fraction),
		),
	)

private fun calculatePageCurlBackPath(
	width: Float,
	height: Float,
	topCurlOffset: Offset,
	bottomCurlOffset: Offset,
	isReversed: Boolean,
): List<Offset> {
	val path = mutableListOf<Offset>()
	if (isReversed) {
		if (topCurlOffset.x > 0f) {
			path += topCurlOffset
			path += Offset(0f, topCurlOffset.y)
		} else {
			val intersection = lineLineIntersection(
				topCurlOffset,
				bottomCurlOffset,
				Offset(0f, 0f),
				Offset(0f, height),
			) ?: Offset.Zero
			path += intersection
			path += intersection
		}
		if (bottomCurlOffset.x > 0f) {
			path += Offset(0f, height)
			path += bottomCurlOffset
		} else {
			val intersection = lineLineIntersection(
				topCurlOffset,
				bottomCurlOffset,
				Offset(0f, 0f),
				Offset(0f, height),
			) ?: Offset(0f, height)
			path += intersection
			path += intersection
		}
	} else {
		if (topCurlOffset.x < width) {
			path += topCurlOffset
			path += Offset(width, topCurlOffset.y)
		} else {
			val intersection = lineLineIntersection(
				topCurlOffset,
				bottomCurlOffset,
				Offset(width, 0f),
				Offset(width, height),
			) ?: Offset(width, 0f)
			path += intersection
			path += intersection
		}
		if (bottomCurlOffset.x < width) {
			path += Offset(width, height)
			path += bottomCurlOffset
		} else {
			val intersection = lineLineIntersection(
				topCurlOffset,
				bottomCurlOffset,
				Offset(width, 0f),
				Offset(width, height),
			) ?: Offset(width, height)
			path += intersection
			path += intersection
		}
	}
	return path
}

private fun lineLineIntersection(
	line1a: Offset,
	line1b: Offset,
	line2a: Offset,
	line2b: Offset,
): Offset? {
	val denominator = (line1a.x - line1b.x) * (line2a.y - line2b.y) -
		(line1a.y - line1b.y) * (line2a.x - line2b.x)
	if (denominator == 0f) return null
	val x1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.x - line2b.x)
	val x2 = (line1a.x - line1b.x) * (line2a.x * line2b.y - line2a.y * line2b.x)
	val y1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.y - line2b.y)
	val y2 = (line1a.y - line1b.y) * (line2a.x * line2b.y - line2a.y * line2b.x)
	return Offset((x1 - x2) / denominator, (y1 - y2) / denominator)
}

private fun List<Offset>.toPath(): Path = Path().apply {
	forEachIndexed { index, point ->
		if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
	}
	close()
}

internal const val READER_PAGE_CAMERA_DISTANCE = 20_000f

private const val PAGE_CURL_DRAG_EPSILON = 0.001f

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
