package org.skepsun.kototoro.space.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceCockpitRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import javax.inject.Inject

enum class SpaceWorkbenchMode {
	HIDDEN,
	OVERLAY,
	COCKPIT,
}

data class SpaceUiState(
	val activeSpaceId: SpaceId = BuiltInSpaces.Manga,
	val switcherVisible: Boolean = false,
	val workbenchMode: SpaceWorkbenchMode = SpaceWorkbenchMode.HIDDEN,
	val switchInProgress: Boolean = false,
	val switcherEnabled: Boolean = false,
	val persistentNavigationEnabled: Boolean = false,
	val spaces: List<SpaceContext> = BuiltInSpaces.contexts,
) {
	val workbenchVisible: Boolean
		get() = workbenchMode != SpaceWorkbenchMode.HIDDEN
}

sealed interface SpaceAction {
	data object OpenSwitcher : SpaceAction
	data object DismissSwitcher : SpaceAction
	data object OpenWorkbench : SpaceAction
	data object DismissWorkbench : SpaceAction
	data object PinWorkbench : SpaceAction
	data object UnpinWorkbench : SpaceAction
	data class SelectSpace(val spaceId: SpaceId) : SpaceAction
}

@HiltViewModel
class SpaceViewModel @Inject constructor(
	private val repository: SpaceRepository,
	catalogRepository: SpaceCatalogRepository,
	featureFlagsRepository: SpaceFeatureFlagsRepository,
	private val cockpitRepository: SpaceCockpitRepository,
) : ViewModel() {

	private val transientState = MutableStateFlow(SpaceUiState())

	val uiState = combine(
		repository.activeSpace,
		featureFlagsRepository.flags,
		catalogRepository.spaces,
		transientState,
		cockpitRepository.isEnabled,
	) { activeSpace, flags, spaces, transient, cockpitEnabled ->
		transient.copy(
			activeSpaceId = activeSpace,
			switcherEnabled = flags.effectiveSwitcherEnabled,
			persistentNavigationEnabled = flags.effectivePersistentNavigationEnabled,
			spaces = spaces,
			switcherVisible = transient.switcherVisible && flags.effectiveSwitcherEnabled,
			workbenchMode = when {
				!flags.effectiveSwitcherEnabled -> SpaceWorkbenchMode.HIDDEN
				cockpitEnabled -> SpaceWorkbenchMode.COCKPIT
				else -> transient.workbenchMode
			},
		)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = SpaceUiState(
			activeSpaceId = repository.activeSpace.value,
			switcherEnabled = featureFlagsRepository.flags.value.effectiveSwitcherEnabled,
			persistentNavigationEnabled = featureFlagsRepository.flags.value.effectivePersistentNavigationEnabled,
			spaces = catalogRepository.spaces.value,
		),
	)

	fun onAction(action: SpaceAction) {
		when (action) {
			SpaceAction.OpenSwitcher -> transientState.update { state ->
				state.copy(
					switcherVisible = uiState.value.switcherEnabled,
					workbenchMode = SpaceWorkbenchMode.HIDDEN,
				)
			}
			SpaceAction.DismissSwitcher -> transientState.update { it.copy(switcherVisible = false) }
			SpaceAction.OpenWorkbench -> transientState.update { state ->
				state.copy(
					switcherVisible = false,
					workbenchMode = if (uiState.value.switcherEnabled) {
						SpaceWorkbenchMode.OVERLAY
					} else {
						SpaceWorkbenchMode.HIDDEN
					},
				)
			}
			SpaceAction.DismissWorkbench -> transientState.update {
				it.copy(workbenchMode = SpaceWorkbenchMode.HIDDEN)
			}
			SpaceAction.PinWorkbench -> transientState.update { state ->
				cockpitRepository.setEnabled(uiState.value.switcherEnabled)
				state.copy(
					switcherVisible = false,
					workbenchMode = if (uiState.value.switcherEnabled) {
						SpaceWorkbenchMode.COCKPIT
					} else {
						SpaceWorkbenchMode.HIDDEN
					},
				)
			}
			SpaceAction.UnpinWorkbench -> transientState.update {
				cockpitRepository.setEnabled(false)
				it.copy(workbenchMode = SpaceWorkbenchMode.HIDDEN)
			}
			is SpaceAction.SelectSpace -> viewModelScope.launch { selectSpaceAndAwait(action.spaceId) }
		}
	}

	suspend fun selectSpaceAndAwait(spaceId: SpaceId): Boolean {
		if (!uiState.value.switcherEnabled || uiState.value.switchInProgress) return false
		transientState.update { it.copy(switchInProgress = true) }
		return try {
			repository.activate(spaceId)
			true
		} catch (error: Throwable) {
			if (error is CancellationException) throw error
			false
		} finally {
			transientState.update { state ->
				state.copy(
					switchInProgress = false,
					switcherVisible = false,
					workbenchMode = if (state.workbenchMode == SpaceWorkbenchMode.COCKPIT) {
						SpaceWorkbenchMode.COCKPIT
					} else {
						SpaceWorkbenchMode.HIDDEN
					},
				)
			}
		}
	}
}
