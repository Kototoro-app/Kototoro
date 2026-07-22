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
