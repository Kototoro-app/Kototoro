package org.skepsun.kototoro.space.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.skepsun.kototoro.space.domain.SpaceId

internal enum class SpaceWorkbenchDragPhase {
	IDLE,
	DRAGGING,
	SETTLING,
}

internal sealed interface SpaceWorkbenchDropOutcome {
	data object Dismiss : SpaceWorkbenchDropOutcome
	data class Select(val spaceId: SpaceId) : SpaceWorkbenchDropOutcome
}

@Stable
internal class SpaceWorkbenchGestureState {

	var phase by mutableStateOf(SpaceWorkbenchDragPhase.IDLE)
		private set

	var dragPosition by mutableStateOf<Offset?>(null)
		private set

	var hoveredSpaceId by mutableStateOf<SpaceId?>(null)
		private set

	private var dragOrigin: Offset? = null
	private var hoveredSpaceCenter: Offset? = null
	private var settlePosition: Offset? = null
	private var pendingOutcome: SpaceWorkbenchDropOutcome? = null

	val isActive: Boolean
		get() = phase != SpaceWorkbenchDragPhase.IDLE

	val proxyPosition: Offset?
		get() = when (phase) {
			SpaceWorkbenchDragPhase.IDLE -> null
			SpaceWorkbenchDragPhase.DRAGGING -> magnetizedPosition()
			SpaceWorkbenchDragPhase.SETTLING -> settlePosition
		}

	fun start(position: Offset) {
		dragOrigin = position
		dragPosition = position
		hoveredSpaceId = null
		hoveredSpaceCenter = null
		settlePosition = null
		pendingOutcome = null
		phase = SpaceWorkbenchDragPhase.DRAGGING
	}

	fun move(position: Offset) {
		if (phase != SpaceWorkbenchDragPhase.DRAGGING) return
		dragPosition = position
	}

	fun updateHoveredSpace(spaceId: SpaceId?, center: Offset?) {
		if (phase != SpaceWorkbenchDragPhase.DRAGGING) return
		hoveredSpaceId = spaceId
		hoveredSpaceCenter = center.takeIf { spaceId != null }
	}

	fun release(activeSpaceId: SpaceId) {
		if (phase != SpaceWorkbenchDragPhase.DRAGGING) return
		val targetSpaceId = hoveredSpaceId
		if (targetSpaceId == null) {
			reset()
			return
		}
		settlePosition = hoveredSpaceCenter ?: dragPosition
		pendingOutcome = if (targetSpaceId == activeSpaceId) {
			SpaceWorkbenchDropOutcome.Dismiss
		} else {
			SpaceWorkbenchDropOutcome.Select(targetSpaceId)
		}
		phase = SpaceWorkbenchDragPhase.SETTLING
	}

	fun cancel() {
		if (phase != SpaceWorkbenchDragPhase.DRAGGING) return
		settlePosition = dragOrigin ?: dragPosition
		pendingOutcome = SpaceWorkbenchDropOutcome.Dismiss
		phase = SpaceWorkbenchDragPhase.SETTLING
	}

	fun completeSettling(): SpaceWorkbenchDropOutcome? {
		if (phase != SpaceWorkbenchDragPhase.SETTLING) return null
		val outcome = pendingOutcome
		reset()
		return outcome
	}

	fun reset() {
		phase = SpaceWorkbenchDragPhase.IDLE
		dragOrigin = null
		dragPosition = null
		hoveredSpaceId = null
		hoveredSpaceCenter = null
		settlePosition = null
		pendingOutcome = null
	}

	private fun magnetizedPosition(): Offset? {
		val position = dragPosition ?: return null
		val target = hoveredSpaceCenter ?: return position
		return Offset(
			x = position.x + (target.x - position.x) * WORKBENCH_MAGNETISM,
			y = position.y + (target.y - position.y) * WORKBENCH_MAGNETISM,
		)
	}

	private companion object {
		const val WORKBENCH_MAGNETISM = 0.28f
	}
}

@Composable
internal fun rememberSpaceWorkbenchGestureState(): SpaceWorkbenchGestureState =
	remember { SpaceWorkbenchGestureState() }
