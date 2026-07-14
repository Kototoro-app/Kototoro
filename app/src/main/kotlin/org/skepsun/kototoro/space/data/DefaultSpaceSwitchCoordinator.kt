package org.skepsun.kototoro.space.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchCoordinator
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.domain.SpaceSwitchResult
import org.skepsun.kototoro.space.domain.SpaceSwitchState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceSwitchCoordinator @Inject constructor(
	private val spaceRepository: SpaceRepository,
) : SpaceSwitchCoordinator {

	private val mutex = Mutex()
	private val mutableState = MutableStateFlow(SpaceSwitchState())
	override val state: StateFlow<SpaceSwitchState> = mutableState.asStateFlow()

	override suspend fun requestSwitch(
		target: SpaceId,
		origin: SpaceSwitchOrigin,
		availability: SpaceSwitchAvailability,
		progressFlusher: SpaceProgressFlusher,
	): SpaceSwitchResult = mutex.withLock {
		if (target == spaceRepository.activeSpace.value) {
			return@withLock SpaceSwitchResult.AlreadyActive(target)
		}
		when (availability) {
			SpaceSwitchAvailability.UNAVAILABLE -> return@withLock SpaceSwitchResult.Unavailable
			SpaceSwitchAvailability.CONFIRM_REQUIRED -> return@withLock SpaceSwitchResult.ConfirmationRequired
			else -> Unit
		}
		mutableState.value = SpaceSwitchState(
			inProgress = true,
			targetSpaceId = target,
			origin = origin,
		)
		try {
			if (availability == SpaceSwitchAvailability.SAVE_AND_SWITCH) {
				progressFlusher.flush()
			}
			spaceRepository.activate(target)
			SpaceSwitchResult.Success(target)
		} catch (error: Throwable) {
			if (error is CancellationException) throw error
			SpaceSwitchResult.Failed(error)
		} finally {
			mutableState.value = SpaceSwitchState()
		}
	}
}
