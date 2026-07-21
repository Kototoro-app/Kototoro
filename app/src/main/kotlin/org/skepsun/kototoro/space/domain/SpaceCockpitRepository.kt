package org.skepsun.kototoro.space.domain

import kotlinx.coroutines.flow.StateFlow

interface SpaceCockpitRepository {
	val isEnabled: StateFlow<Boolean>

	fun setEnabled(enabled: Boolean)
}
