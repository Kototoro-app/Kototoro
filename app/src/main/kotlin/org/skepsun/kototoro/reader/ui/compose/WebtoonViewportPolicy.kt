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
)

data class WebtoonCanvasOffsetBounds(
	val minX: Float,
	val maxX: Float,
	val minY: Float,
	val maxY: Float,
)

internal fun resolveWebtoonAnchorPosition(pageKeys: List<Long>, anchorPageKey: Long): Int {
	return pageKeys.indexOf(anchorPageKey)
}

internal fun requiresWebtoonAnchorRestore(
	previousPageKeys: List<Long>,
	pageKeys: List<Long>,
	anchorPageKey: Long,
): Boolean {
	val previousPosition = previousPageKeys.indexOf(anchorPageKey)
	val position = pageKeys.indexOf(anchorPageKey)
	return previousPosition >= 0 && position >= 0 && previousPosition != position
}

internal fun resolveWebtoonVisiblePageRange(
	pageKeys: List<Long>,
	lowerPageKey: Long,
	upperPageKey: Long,
): IntRange? {
	val lowerPosition = pageKeys.indexOf(lowerPageKey)
	val upperPosition = pageKeys.indexOf(upperPageKey)
	return if (lowerPosition >= 0 && upperPosition >= lowerPosition) {
		lowerPosition..upperPosition
	} else {
		null
	}
}

internal data class WebtoonVisibleItem(
	val pageKey: Long,
	val offsetPx: Int,
	val sizePx: Int,
)

internal fun resolveLastEndVisibleWebtoonPageKey(
	items: List<WebtoonVisibleItem>,
	viewportStartPx: Int,
	viewportEndPx: Int,
): Long? {
	return items.lastOrNull { item ->
		val itemEnd = item.offsetPx + item.sizePx
		itemEnd > viewportStartPx && itemEnd <= viewportEndPx
	}?.pageKey
}

internal fun shouldTrackWebtoonViewport(
	isAnchorRestorePending: Boolean,
	isPageWindowAnchorShifted: Boolean,
	viewportConfigurationChanged: Boolean,
	isViewportLayoutReady: Boolean,
): Boolean = !isAnchorRestorePending &&
	!isPageWindowAnchorShifted &&
	!viewportConfigurationChanged &&
	isViewportLayoutReady

internal fun isWebtoonViewportLayoutReady(
	visibleItemSizesPx: List<Int>,
	viewportHeightPx: Int,
): Boolean {
	return viewportHeightPx > 1 && visibleItemSizesPx.any { it > 1 }
}

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

/** Keeps the zoomed-out canvas large enough to fill the viewport while its content is scaled. */
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
	if (!scale.isFinite() || !desiredY.isFinite() || !boundedY.isFinite()) return 0
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
		return WebtoonViewportMeasurement(itemHeightPx = viewport)
	}
	val sourceHeight = (imageHeightPx.toFloat() * availableWidthPx / imageWidthPx).toInt().coerceAtLeast(1)
	return WebtoonViewportMeasurement(itemHeightPx = sourceHeight)
}
