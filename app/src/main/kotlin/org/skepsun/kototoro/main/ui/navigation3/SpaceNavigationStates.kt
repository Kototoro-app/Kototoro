package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
}

@Composable
fun rememberSpaceNavigationStates(
	initialTopLevel: TopLevelNavKey,
): SpaceNavigationStates {
	// Keep fixed composition slots so every built-in Space retains its own saved root and top-level stacks.
	val manga = rememberSpaceNavigationState(initialTopLevel)
	val novel = rememberSpaceNavigationState(initialTopLevel)
	val anime = rememberSpaceNavigationState(initialTopLevel)
	return remember(manga, novel, anime) {
		SpaceNavigationStates(
			mapOf(
				BuiltInSpaces.Manga to manga,
				BuiltInSpaces.Novel to novel,
				BuiltInSpaces.Anime to anime,
			),
		)
	}
}

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
