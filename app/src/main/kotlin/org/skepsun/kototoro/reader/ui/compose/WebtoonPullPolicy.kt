package org.skepsun.kototoro.reader.ui.compose

internal data class WebtoonPullState(
	val topDistancePx: Float = 0f,
	val bottomDistancePx: Float = 0f,
)

internal enum class WebtoonPullDirection {
	PREVIOUS,
	NEXT,
}

internal fun WebtoonPullState.pullAtBoundary(
	availableY: Float,
	canScrollBackward: Boolean,
	canScrollForward: Boolean,
	maxDistancePx: Float,
): WebtoonPullState = when {
	availableY > 0f && !canScrollBackward -> copy(
		topDistancePx = (topDistancePx + availableY).coerceAtMost(maxDistancePx),
		bottomDistancePx = 0f,
	)
	availableY < 0f && !canScrollForward -> copy(
		topDistancePx = 0f,
		bottomDistancePx = (bottomDistancePx - availableY).coerceAtMost(maxDistancePx),
	)
	else -> this
}

internal fun WebtoonPullState.retract(availableY: Float): WebtoonPullState = when {
	topDistancePx > 0f && availableY < 0f -> copy(topDistancePx = (topDistancePx + availableY).coerceAtLeast(0f))
	bottomDistancePx > 0f && availableY > 0f -> copy(
		bottomDistancePx = (bottomDistancePx - availableY).coerceAtLeast(0f),
	)
	else -> this
}

internal fun WebtoonPullState.release(thresholdPx: Float): WebtoonPullDirection? = when {
	topDistancePx >= thresholdPx -> WebtoonPullDirection.PREVIOUS
	bottomDistancePx >= thresholdPx -> WebtoonPullDirection.NEXT
	else -> null
}
