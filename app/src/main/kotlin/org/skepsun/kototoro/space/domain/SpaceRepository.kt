package org.skepsun.kototoro.space.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

interface SpaceRepository {

	val activeSpace: StateFlow<SpaceId>

	suspend fun activate(spaceId: SpaceId)
}

data class SpaceFeatureFlags(
	val entitySpaceEnabled: Boolean,
	val spaceSwitcherEnabled: Boolean,
	val spacePersistentNavigationEnabled: Boolean,
	val spaceImmersiveSwitchEnabled: Boolean,
	val spaceRoutePreferencesEnabled: Boolean,
) {
	val effectiveSwitcherEnabled: Boolean
		get() = entitySpaceEnabled && spaceSwitcherEnabled

	val effectivePersistentNavigationEnabled: Boolean
		get() = effectiveSwitcherEnabled && spacePersistentNavigationEnabled

	val effectiveImmersiveSwitchEnabled: Boolean
		get() = effectiveSwitcherEnabled && spaceImmersiveSwitchEnabled

	val effectiveRoutePreferencesEnabled: Boolean
		get() = entitySpaceEnabled && spaceRoutePreferencesEnabled
}

interface SpaceFeatureFlagsRepository {
	val flags: StateFlow<SpaceFeatureFlags>
}

fun SpaceRepository.observeActiveSpaceScope(
	featureFlagsRepository: SpaceFeatureFlagsRepository,
): Flow<SpaceId?> = combine(
	activeSpace,
	featureFlagsRepository.flags,
) { activeSpace, flags ->
	activeSpace.takeIf { flags.effectiveSwitcherEnabled }
}.distinctUntilChanged()
