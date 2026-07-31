package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

internal fun isWebtoonDoubleTapCandidate(
	firstTapPosition: Offset,
	firstTapUpTimeMillis: Long,
	secondTapPosition: Offset,
	secondTapDownTimeMillis: Long,
	minTimeMillis: Long,
	timeoutMillis: Long,
	doubleTapSlop: Float,
): Boolean {
	return isTapGridDoubleTapCandidate(
		previousPosition = firstTapPosition,
		previousTapAt = firstTapUpTimeMillis,
		position = secondTapPosition,
		now = secondTapDownTimeMillis,
		minTimeMillis = minTimeMillis,
		timeoutMillis = timeoutMillis,
		doubleTapSlop = doubleTapSlop,
	)
}

internal fun hasExceededWebtoonTapSlop(
	start: Offset,
	current: Offset,
	touchSlop: Float,
): Boolean = hypot(current.x - start.x, current.y - start.y) > touchSlop
