package org.skepsun.kototoro.space.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import javax.inject.Inject

data class SpaceUiState(
	val activeSpaceId: SpaceId = BuiltInSpaces.Manga,
	val switcherVisible: Boolean = false,
	val switchInProgress: Boolean = false,
	val switcherEnabled: Boolean = false,
)

sealed interface SpaceAction {
	data object OpenSwitcher : SpaceAction
	data object DismissSwitcher : SpaceAction
	data class SelectSpace(val spaceId: SpaceId) : SpaceAction
}

@HiltViewModel
class SpaceViewModel @Inject constructor(
	private val repository: SpaceRepository,
	featureFlagsRepository: SpaceFeatureFlagsRepository,
) : ViewModel() {

	private val transientState = MutableStateFlow(SpaceUiState())

	val uiState = combine(
		repository.activeSpace,
		featureFlagsRepository.flags,
		transientState,
	) { activeSpace, flags, transient ->
		transient.copy(
			activeSpaceId = activeSpace,
			switcherEnabled = flags.effectiveSwitcherEnabled,
			switcherVisible = transient.switcherVisible && flags.effectiveSwitcherEnabled,
		)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = SpaceUiState(activeSpaceId = repository.activeSpace.value),
	)

	fun onAction(action: SpaceAction) {
		when (action) {
			SpaceAction.OpenSwitcher -> transientState.update { state ->
				state.copy(switcherVisible = uiState.value.switcherEnabled)
			}
			SpaceAction.DismissSwitcher -> transientState.update { it.copy(switcherVisible = false) }
			is SpaceAction.SelectSpace -> selectSpace(action.spaceId)
		}
	}

	private fun selectSpace(spaceId: SpaceId) {
		if (!uiState.value.switcherEnabled || uiState.value.switchInProgress) return
		viewModelScope.launch {
			transientState.update { it.copy(switchInProgress = true) }
			runCatching { repository.activate(spaceId) }
			transientState.update {
				it.copy(
					switchInProgress = false,
					switcherVisible = false,
				)
			}
		}
	}
}
