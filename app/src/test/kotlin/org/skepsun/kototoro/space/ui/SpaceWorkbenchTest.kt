package org.skepsun.kototoro.space.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

class SpaceWorkbenchTest {

	private val orderedSpaceIds = listOf(
		BuiltInSpaces.Manga,
		BuiltInSpaces.Novel,
		BuiltInSpaces.Anime,
	)
	private val cardBounds = mapOf(
		BuiltInSpaces.Manga to Rect(900f, 100f, 1080f, 260f),
		BuiltInSpaces.Novel to Rect(900f, 280f, 1080f, 440f),
		BuiltInSpaces.Anime to Rect(900f, 460f, 1080f, 620f),
	)

	@Test
	fun `drag position inside card resolves its space`() {
		assertEquals(
			BuiltInSpaces.Novel,
			resolveSpaceWorkbenchDropTarget(Offset(950f, 320f), orderedSpaceIds, cardBounds),
		)
	}

	@Test
	fun `missing or outside drag position has no drop target`() {
		assertNull(resolveSpaceWorkbenchDropTarget(null, orderedSpaceIds, cardBounds))
		assertNull(
			resolveSpaceWorkbenchDropTarget(Offset(500f, 320f), orderedSpaceIds, cardBounds),
		)
	}

	@Test
	fun `overlapping bounds use visible space order deterministically`() {
		val overlappingBounds = cardBounds + mapOf<SpaceId, Rect>(
			BuiltInSpaces.Novel to Rect(900f, 100f, 1080f, 260f),
		)

		assertEquals(
			BuiltInSpaces.Manga,
			resolveSpaceWorkbenchDropTarget(Offset(950f, 180f), orderedSpaceIds, overlappingBounds),
		)
	}

	@Test
	fun `session route maps to lightweight workbench location`() {
		assertEquals(
			SpaceWorkbenchLocation.Details,
			session(SpaceRouteSnapshot.WorkDetails(entityId = 7L, requestedProjectionId = null))
				.toWorkbenchLocation(),
		)
		assertEquals(
			SpaceWorkbenchLocation.ContentList("ExampleSource"),
			session(SpaceRouteSnapshot.ContentList("ExampleSource")).toWorkbenchLocation(),
		)
		assertNull(session(SpaceRouteSnapshot.TopLevel("home")).toWorkbenchLocation())
	}

	private fun session(resumeRoute: SpaceRouteSnapshot) = SpaceSessionSnapshot(
		spaceId = BuiltInSpaces.Manga,
		selectedTopLevel = "home",
		resumeRoute = resumeRoute,
		stacks = emptyMap(),
		lastAccessed = 1L,
		updatedAt = 1L,
	)
}
