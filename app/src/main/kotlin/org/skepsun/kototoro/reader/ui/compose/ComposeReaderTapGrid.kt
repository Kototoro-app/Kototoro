package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.domain.TapGridArea
import kotlin.math.abs
import kotlin.math.hypot

internal fun resolveTapGridArea(position: Offset, size: IntSize): TapGridArea? {
	if (size.width <= 0 || size.height <= 0) return null
	val column = (position.x / (size.width / 3f)).toInt().coerceIn(0, 2)
	val row = (position.y / (size.height / 3f)).toInt().coerceIn(0, 2)
	return TapGridArea.entries[row * 3 + column]
}

internal fun isTapGridDoubleTapCandidate(
	previousPosition: Offset?,
	previousTapAt: Long,
	position: Offset,
	now: Long,
	minTimeMillis: Long = 0L,
	timeoutMillis: Long,
	doubleTapSlop: Float,
): Boolean = previousPosition != null &&
	now - previousTapAt in minTimeMillis..timeoutMillis &&
	hypot(position.x - previousPosition.x, position.y - previousPosition.y) < doubleTapSlop

internal fun Modifier.readerTapGestures(
	onInteraction: () -> Unit,
	onTap: (TapGridArea) -> Unit,
	onLongTap: (TapGridArea, Offset, IntSize) -> Unit,
	doubleTapSlop: Float,
): Modifier = pointerInput(onInteraction, onTap, onLongTap, doubleTapSlop) {
	coroutineScope {
		var downPosition: Offset? = null
		var moved = false
		var longPressDispatched = false
		var doubleTapInProgress = false
		var longPressJob: Job? = null
		var pendingTapJob: Job? = null
		var lastTapPosition: Offset? = null
		var lastTapAt = 0L

		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				val change = event.changes.firstOrNull() ?: continue
				val hasMultiplePointers = event.changes.count { it.pressed } > 1
				if (hasMultiplePointers) {
					moved = true
					longPressJob?.cancel()
				}
				when {
					change.changedToDownIgnoreConsumed() -> {
						downPosition = change.position
						moved = hasMultiplePointers
						longPressDispatched = false
						doubleTapInProgress = isTapGridDoubleTapCandidate(
							previousPosition = lastTapPosition,
							previousTapAt = lastTapAt,
							position = change.position,
							now = change.uptimeMillis,
							minTimeMillis = viewConfiguration.doubleTapMinTimeMillis,
							timeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
							doubleTapSlop = doubleTapSlop,
						)
						if (doubleTapInProgress) {
							pendingTapJob?.cancel()
							pendingTapJob = null
							lastTapPosition = null
							lastTapAt = 0L
						}
						onInteraction()
						longPressJob?.cancel()
						if (!moved) {
							longPressJob = launch {
								delay(viewConfiguration.longPressTimeoutMillis)
								val position = downPosition
								if (!moved && position != null) {
									resolveTapGridArea(position, size)?.let { area ->
										longPressDispatched = true
										pendingTapJob?.cancel()
										pendingTapJob = null
										lastTapPosition = null
										lastTapAt = 0L
										onLongTap(area, position, size)
									}
								}
							}
						}
					}
					downPosition != null && change.pressed -> {
						val start = downPosition ?: continue
						if (abs(change.position.x - start.x) > viewConfiguration.touchSlop ||
							abs(change.position.y - start.y) > viewConfiguration.touchSlop
						) {
							moved = true
							longPressJob?.cancel()
						}
					}
					change.changedToUpIgnoreConsumed() -> {
						longPressJob?.cancel()
						val position = downPosition
						if (!moved && !longPressDispatched && position != null && !doubleTapInProgress) {
							lastTapPosition = position
							lastTapAt = change.uptimeMillis
							pendingTapJob = launch {
								delay(viewConfiguration.doubleTapTimeoutMillis)
								resolveTapGridArea(position, size)?.let(onTap)
								if (lastTapPosition == position) lastTapPosition = null
							}
						} else if (longPressDispatched) {
							lastTapPosition = null
							lastTapAt = 0L
						}
						downPosition = null
						doubleTapInProgress = false
					}
				}
			}
		}
	}
}
