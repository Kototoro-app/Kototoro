package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId

@Stable
class SpaceNavigationState internal constructor(
	val navController: NavHostController,
	val mainNavState: MainNavState,
)

@Stable
class SpaceNavigationStates internal constructor(
	private val states: Map<SpaceId, SpaceNavigationState>,
) {
	operator fun get(spaceId: SpaceId): SpaceNavigationState = states.getValue(spaceId)
	operator fun contains(spaceId: SpaceId): Boolean = spaceId in states
}

@Composable
fun rememberSpaceNavigationStates(
	initialTopLevel: TopLevelNavKey,
	activeSpaceId: SpaceId = BuiltInSpaces.Manga,
): SpaceNavigationStates {
	val recentCustomIds = remember { mutableStateListOf<SpaceId>() }
	LaunchedEffect(activeSpaceId) {
		if (activeSpaceId.value.startsWith("custom:")) {
			recentCustomIds.remove(activeSpaceId)
			recentCustomIds.add(activeSpaceId)
			while (recentCustomIds.size > MAX_RECENT_CUSTOM_NAVIGATION_STATES) {
				recentCustomIds.removeAt(0)
			}
		}
	}
	val ids = buildList {
		addAll(BuiltInSpaces.contexts.map { it.id })
		addAll(recentCustomIds)
		if (activeSpaceId.value.startsWith("custom:") && activeSpaceId !in this) add(activeSpaceId)
	}
	val states = ids.associateWith { id ->
		key(id.value) { rememberSpaceNavigationState(initialTopLevel) }
	}
	return remember(states) {
		SpaceNavigationStates(states)
	}
}

private const val MAX_RECENT_CUSTOM_NAVIGATION_STATES = 3

fun resolveNavigationSpaceId(
	activeSpaceId: SpaceId,
	persistentNavigationEnabled: Boolean,
): SpaceId = activeSpaceId.takeIf { persistentNavigationEnabled } ?: BuiltInSpaces.Manga

@Composable
private fun rememberSpaceNavigationState(
	initialTopLevel: TopLevelNavKey,
): SpaceNavigationState {
	val mainNavState = rememberMainNavState(initialTopLevel)
	val navController = rememberNavController()
	return remember(navController, mainNavState) {
		SpaceNavigationState(
			navController = navController,
			mainNavState = mainNavState,
		)
	}
}
