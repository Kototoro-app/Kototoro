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

internal fun resolveTapGridArea(position: Offset, size: IntSize): TapGridArea? {
	if (size.width <= 0 || size.height <= 0) return null
	val column = (position.x / (size.width / 3f)).toInt().coerceIn(0, 2)
	val row = (position.y / (size.height / 3f)).toInt().coerceIn(0, 2)
	return TapGridArea.entries[row * 3 + column]
}

internal fun Modifier.readerTapGrid(
	enabled: Boolean,
	onInteraction: () -> Unit,
	onTap: (TapGridArea) -> Unit,
	onLongTap: (TapGridArea, Offset, IntSize) -> Unit,
): Modifier = if (!enabled) this else pointerInput(onInteraction, onTap, onLongTap) {
	coroutineScope {
		var downPosition: Offset? = null
		var moved = false
		var longPressDispatched = false
		var longPressJob: Job? = null
		var pendingTapJob: Job? = null
		var lastTapPosition: Offset? = null
		var lastTapAt = 0L

		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Final)
				val change = event.changes.firstOrNull() ?: continue
				when {
					change.changedToDownIgnoreConsumed() -> {
						downPosition = change.position
						moved = false
						longPressDispatched = false
						onInteraction()
						longPressJob?.cancel()
						longPressJob = launch {
							delay(viewConfiguration.longPressTimeoutMillis)
							val position = downPosition
							if (!moved && position != null) {
								resolveTapGridArea(position, size)?.let { area ->
									longPressDispatched = true
									onLongTap(area, position, size)
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
						if (!moved && !longPressDispatched && position != null) {
							val now = System.currentTimeMillis()
							val previous = lastTapPosition
							val isDoubleTap = previous != null &&
								now - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
								abs(position.x - previous.x) <= viewConfiguration.touchSlop &&
								abs(position.y - previous.y) <= viewConfiguration.touchSlop
							if (isDoubleTap) {
								pendingTapJob?.cancel()
								lastTapPosition = null
							} else {
								lastTapPosition = position
								lastTapAt = now
								pendingTapJob = launch {
									delay(viewConfiguration.doubleTapTimeoutMillis)
									resolveTapGridArea(position, size)?.let(onTap)
									if (lastTapPosition == position) lastTapPosition = null
								}
							}
						}
						downPosition = null
					}
				}
			}
		}
	}
}
