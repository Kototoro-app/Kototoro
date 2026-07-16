package org.skepsun.kototoro.space.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceSessionRepository
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionValidator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class SpaceNavigationSessionUiState(
	val enabled: Boolean = false,
	val restorationReady: Boolean = true,
	val sessions: Map<SpaceId, SpaceSessionSnapshot> = emptyMap(),
)

@HiltViewModel
class SpaceNavigationSessionViewModel @Inject constructor(
	private val repository: SpaceSessionRepository,
	private val validator: SpaceSessionValidator,
	private val catalogRepository: SpaceCatalogRepository,
	featureFlagsRepository: SpaceFeatureFlagsRepository,
) : ViewModel() {
	private val saveMutex = Mutex()
	private val saveGeneration = AtomicLong(0L)
	private val latestSaveGeneration = ConcurrentHashMap<SpaceId, Long>()
	private val latestRequestedSnapshots = ConcurrentHashMap<SpaceId, SpaceSessionSnapshot>()

	private val mutableUiState = MutableStateFlow(
		SpaceNavigationSessionUiState(
			enabled = featureFlagsRepository.flags.value.effectivePersistentNavigationEnabled,
			restorationReady = !featureFlagsRepository.flags.value.effectivePersistentNavigationEnabled,
		),
	)
	val uiState: StateFlow<SpaceNavigationSessionUiState> = mutableUiState.asStateFlow()

	init {
		viewModelScope.launch {
			combine(
				featureFlagsRepository.flags.map { it.effectivePersistentNavigationEnabled },
				catalogRepository.spaces,
			) { enabled, spaces -> enabled to spaces }
				.distinctUntilChanged()
				.collectLatest { (enabled, spaces) -> onCatalogChanged(enabled, spaces) }
		}
	}

	fun save(snapshot: SpaceSessionSnapshot) {
		if (!uiState.value.enabled || !uiState.value.restorationReady) return
		val previous = latestRequestedSnapshots[snapshot.spaceId] ?: uiState.value.sessions[snapshot.spaceId]
		if (previous?.hasSameNavigationState(snapshot) == true) return
		latestRequestedSnapshots[snapshot.spaceId] = snapshot
		val generation = saveGeneration.incrementAndGet()
		latestSaveGeneration[snapshot.spaceId] = generation
		viewModelScope.launch {
			saveMutex.withLock {
				if (latestSaveGeneration[snapshot.spaceId] != generation) return@withLock
				runCatching { repository.save(snapshot) }.onSuccess {
					if (latestSaveGeneration[snapshot.spaceId] == generation) {
						mutableUiState.update { state ->
							state.copy(sessions = state.sessions + (snapshot.spaceId to snapshot))
						}
					}
				}.onFailure {
					if (latestSaveGeneration[snapshot.spaceId] == generation) {
						latestRequestedSnapshots.remove(snapshot.spaceId, snapshot)
					}
				}
			}
		}
	}

	private suspend fun onCatalogChanged(enabled: Boolean, spaces: List<SpaceContext>) {
		if (!enabled) {
			latestSaveGeneration.clear()
			latestRequestedSnapshots.clear()
			mutableUiState.value = SpaceNavigationSessionUiState()
			return
		}
		mutableUiState.value = SpaceNavigationSessionUiState(enabled = true, restorationReady = false)
		val sessions = spaces.mapNotNull { context ->
			runCatching {
				repository.load(context.id)?.let { validator.validate(it) }
			}
				.getOrNull()
				?.let { context.id to it }
		}.toMap()
		mutableUiState.value = SpaceNavigationSessionUiState(
			enabled = true,
			restorationReady = true,
			sessions = sessions,
		)
	}
}

private fun SpaceSessionSnapshot.hasSameNavigationState(other: SpaceSessionSnapshot): Boolean =
	spaceId == other.spaceId &&
		selectedTopLevel == other.selectedTopLevel &&
		resumeRoute == other.resumeRoute &&
		stacks == other.stacks
