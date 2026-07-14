package org.skepsun.kototoro.space.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

enum class SpaceSwitchAvailability {
	AVAILABLE,
	SAVE_AND_SWITCH,
	CONFIRM_REQUIRED,
	UNAVAILABLE,
}

enum class SpaceSwitchOrigin {
	READER,
	NOVEL_READER,
	VIDEO_PLAYER,
}

fun interface SpaceProgressFlusher {
	suspend fun flush()
}

data class SpaceSwitchState(
	val inProgress: Boolean = false,
	val targetSpaceId: SpaceId? = null,
	val origin: SpaceSwitchOrigin? = null,
)

sealed interface SpaceSwitchResult {
	data class Success(val targetSpaceId: SpaceId) : SpaceSwitchResult
	data class AlreadyActive(val spaceId: SpaceId) : SpaceSwitchResult
	data object Unavailable : SpaceSwitchResult
	data object ConfirmationRequired : SpaceSwitchResult
	data class Failed(val error: Throwable) : SpaceSwitchResult
}

interface SpaceSwitchCoordinator {
	val state: StateFlow<SpaceSwitchState>

	suspend fun requestSwitch(
		target: SpaceId,
		origin: SpaceSwitchOrigin,
		availability: SpaceSwitchAvailability,
		progressFlusher: SpaceProgressFlusher,
	): SpaceSwitchResult
}

suspend fun Job.awaitCompletion() {
	val completion = CompletableDeferred<Throwable?>()
	invokeOnCompletion { error -> completion.complete(error) }
	completion.await()?.let { throw it }
}
