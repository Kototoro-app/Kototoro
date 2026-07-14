package org.skepsun.kototoro.main.ui.navigation3

import androidx.navigation3.runtime.NavBackStack
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

class SpaceNavigationSnapshotMapperTest {

	@Test
	fun `snapshot persists serializable keys without transient payloads`() {
		val state = mainNavState()
		state.navigateTopLevel(HistoryNavKey)
		state.push(ContentListNavKey("SOURCE"))
		state.push(DetailsNavKey(entityId = 42L, requestedProjectionId = 7L))

		val snapshot = state.toSpaceSessionSnapshot(BuiltInSpaces.Novel, timestamp = 100L)

		snapshot.selectedTopLevel shouldBe "history"
		snapshot.stacks.getValue("history") shouldBe listOf(
			SpaceRouteSnapshot.TopLevel("history"),
			SpaceRouteSnapshot.ContentList("SOURCE"),
			SpaceRouteSnapshot.WorkDetails(42L, 7L),
		)
		snapshot.resumeRoute shouldBe SpaceRouteSnapshot.WorkDetails(42L, 7L)
	}

	@Test
	fun `restore rebuilds selected top level and child stack`() {
		val state = mainNavState()
		val snapshot = SpaceSessionSnapshot(
			spaceId = BuiltInSpaces.Anime,
			selectedTopLevel = "favorites",
			resumeRoute = SpaceRouteSnapshot.WorkDetails(9L, null),
			stacks = mapOf(
				"favorites" to listOf(
					SpaceRouteSnapshot.TopLevel("favorites"),
					SpaceRouteSnapshot.WorkDetails(9L, null),
				),
			),
			lastAccessed = 100L,
			updatedAt = 100L,
		)

		state.restoreFromSpaceSession(snapshot)

		state.selectedTopLevel shouldBe FavoritesNavKey
		state.currentStack().toList() shouldBe listOf(
			FavoritesNavKey,
			DetailsNavKey(entityId = 9L, requestedProjectionId = null),
		)
	}

	@Test
	fun `unresolved details key truncates following routes when saving`() {
		val state = mainNavState()
		state.push(DetailsNavKey(requestedProjectionId = 5L))
		state.push(ContentListNavKey("SOURCE"))

		val snapshot = state.toSpaceSessionSnapshot(BuiltInSpaces.Manga, timestamp = 100L)

		snapshot.stacks.getValue("home") shouldBe listOf(SpaceRouteSnapshot.TopLevel("home"))
	}

	private fun mainNavState(): MainNavState {
		var selected: TopLevelNavKey = HomeNavKey
		val stacks = allTopLevelNavKeys.associateWith { key -> NavBackStack<MainNavKey>(key) }
		return MainNavState(
			readSelectedTopLevel = { selected },
			writeSelectedTopLevel = { selected = it },
			stacks = stacks,
		)
	}
}
