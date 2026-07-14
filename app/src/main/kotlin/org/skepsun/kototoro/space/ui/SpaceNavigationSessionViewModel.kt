package org.skepsun.kototoro.space.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceSessionRepository
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionValidator
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
	featureFlagsRepository: SpaceFeatureFlagsRepository,
) : ViewModel() {

	private val mutableUiState = MutableStateFlow(
		SpaceNavigationSessionUiState(
			enabled = featureFlagsRepository.flags.value.effectivePersistentNavigationEnabled,
			restorationReady = !featureFlagsRepository.flags.value.effectivePersistentNavigationEnabled,
		),
	)
	val uiState: StateFlow<SpaceNavigationSessionUiState> = mutableUiState.asStateFlow()

	init {
		viewModelScope.launch {
			featureFlagsRepository.flags
				.map { it.effectivePersistentNavigationEnabled }
				.distinctUntilChanged()
				.collectLatest(::onEnabledChanged)
		}
	}

	fun save(snapshot: SpaceSessionSnapshot) {
		if (!uiState.value.enabled || !uiState.value.restorationReady) return
		viewModelScope.launch {
			runCatching { repository.save(snapshot) }.onSuccess {
				mutableUiState.update { state ->
					state.copy(sessions = state.sessions + (snapshot.spaceId to snapshot))
				}
			}
		}
	}

	private suspend fun onEnabledChanged(enabled: Boolean) {
		if (!enabled) {
			mutableUiState.value = SpaceNavigationSessionUiState()
			return
		}
		mutableUiState.value = SpaceNavigationSessionUiState(enabled = true, restorationReady = false)
		val sessions = BuiltInSpaces.contexts.mapNotNull { context ->
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
