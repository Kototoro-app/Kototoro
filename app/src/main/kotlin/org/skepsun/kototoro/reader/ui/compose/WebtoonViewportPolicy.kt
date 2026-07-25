package org.skepsun.kototoro.reader.ui.compose

import kotlin.math.roundToInt

/**
 * Compose equivalent of WebtoonImageView's measurement contract.
 *
 * A page always reserves one viewport before its source dimensions are known. Once known, it
 * remains at most one viewport high and the excess is scrolled inside that page before the list
 * advances, matching the legacy WebtoonRecyclerView/WebtoonImageView hand-off.
 */
data class WebtoonViewportMeasurement(
	val itemHeightPx: Int,
	val internalScrollRangePx: Int,
)

data class WebtoonInternalScrollConsumption(
	val offsetPx: Int,
	val consumedPx: Int,
)

data class WebtoonCanvasOffsetBounds(
	val minX: Float,
	val maxX: Float,
	val minY: Float,
	val maxY: Float,
)

/** Matches WebtoonScalingFrame's translation bounds for the zoomed webtoon canvas. */
fun resolveWebtoonCanvasOffsetBounds(
	viewportWidthPx: Int,
	viewportHeightPx: Int,
	scale: Float,
): WebtoonCanvasOffsetBounds {
	val safeScale = scale.coerceAtLeast(0.01f)
	val maxX = (viewportWidthPx * (safeScale - 1f) / 2f).coerceAtLeast(0f)
	if (safeScale < 1f) {
		// The layout height is inverse-scaled below. Its center transform already keeps the
		// scaled content flush with the viewport, so an extra negative translation clips the top.
		return WebtoonCanvasOffsetBounds(minX = 0f, maxX = 0f, minY = 0f, maxY = 0f)
	}
	val maxY = (viewportHeightPx * (safeScale - 1f) / 2f).coerceAtLeast(0f)
	return WebtoonCanvasOffsetBounds(
		minX = -maxX,
		maxX = maxX,
		minY = -maxY,
		maxY = maxY,
	)
}

/**
 * A zoomed-out webtoon container must reserve the inverse-scaled viewport. Otherwise a page
 * capped at the normal viewport is rendered shorter than the visible container and leaves a gap.
 */
fun resolveWebtoonLayoutViewportHeight(viewportHeightPx: Int, scale: Float): Int {
	val viewport = viewportHeightPx.coerceAtLeast(1)
	val safeScale = scale.coerceIn(0.5f, 1f)
	return if (safeScale < 1f) {
		(viewport / safeScale).roundToInt().coerceAtLeast(viewport)
	} else {
		viewport
	}
}

fun resolveWebtoonBoundaryHandoff(scale: Float, desiredY: Float, boundedY: Float): Int {
	if (scale <= 1f) return 0
	return ((boundedY - desiredY) / scale).roundToInt()
}

fun measureWebtoonViewport(
	viewportHeightPx: Int,
	availableWidthPx: Int,
	imageWidthPx: Int?,
	imageHeightPx: Int?,
): WebtoonViewportMeasurement {
	val viewport = viewportHeightPx.coerceAtLeast(1)
	if (availableWidthPx <= 0 || imageWidthPx == null || imageHeightPx == null ||
		imageWidthPx <= 0 || imageHeightPx <= 0
	) {
		return WebtoonViewportMeasurement(itemHeightPx = viewport, internalScrollRangePx = 0)
	}
	val sourceHeight = (imageHeightPx.toFloat() * availableWidthPx / imageWidthPx).roundToInt().coerceAtLeast(1)
	return WebtoonViewportMeasurement(
		itemHeightPx = sourceHeight.coerceAtMost(viewport),
		internalScrollRangePx = (sourceHeight - viewport).coerceAtLeast(0),
	)
}

/**
 * Consumes a list-scroll delta inside one long image before the LazyColumn moves to another item.
 * Positive deltas move toward the image bottom; negative deltas move toward its top.
 */
fun consumeWebtoonInternalScroll(
	offsetPx: Int,
	scrollRangePx: Int,
	deltaPx: Int,
): WebtoonInternalScrollConsumption {
	val newOffset = (offsetPx + deltaPx).coerceIn(0, scrollRangePx.coerceAtLeast(0))
	return WebtoonInternalScrollConsumption(
		offsetPx = newOffset,
		consumedPx = newOffset - offsetPx,
	)
}

fun restoreWebtoonInternalScroll(savedOffsetPx: Int, scrollRangePx: Int): Int {
	return savedOffsetPx.coerceIn(0, scrollRangePx.coerceAtLeast(0))
}
