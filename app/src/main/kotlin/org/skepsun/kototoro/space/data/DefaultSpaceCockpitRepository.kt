package org.skepsun.kototoro.space.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.space.domain.SpaceCockpitRepository

@Singleton
class DefaultSpaceCockpitRepository @Inject constructor() : SpaceCockpitRepository {

	private val mutableEnabled = MutableStateFlow(false)

	override val isEnabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

	override fun setEnabled(enabled: Boolean) {
		mutableEnabled.value = enabled
	}
}
