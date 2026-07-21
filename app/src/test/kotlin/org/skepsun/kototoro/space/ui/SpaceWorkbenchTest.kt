package org.skepsun.kototoro.space.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

	@Test
	fun `drag state magnetizes proxy and settles on selected space`() {
		val state = SpaceWorkbenchGestureState()
		state.start(Offset(100f, 100f))
		state.updateHoveredSpace(BuiltInSpaces.Novel, Offset(200f, 300f))
		val proxyPosition = requireNotNull(state.proxyPosition)

		assertEquals(128f, proxyPosition.x, 0.001f)
		assertEquals(156f, proxyPosition.y, 0.001f)
		state.release(BuiltInSpaces.Manga)
		assertEquals(SpaceWorkbenchDragPhase.SETTLING, state.phase)
		assertEquals(Offset(200f, 300f), state.proxyPosition)
		assertEquals(
			SpaceWorkbenchDropOutcome.Select(BuiltInSpaces.Novel),
			state.completeSettling(),
		)
		assertEquals(SpaceWorkbenchDragPhase.IDLE, state.phase)
	}

	@Test
	fun `release without target keeps workbench open`() {
		val state = SpaceWorkbenchGestureState()
		state.start(Offset(100f, 100f))
		state.move(Offset(300f, 400f))

		state.release(BuiltInSpaces.Manga)
		assertEquals(SpaceWorkbenchDragPhase.IDLE, state.phase)
		assertNull(state.proxyPosition)
		assertNull(state.completeSettling())
	}

	@Test
	fun `cancel settles proxy back at drag origin before dismissing`() {
		val state = SpaceWorkbenchGestureState()
		state.start(Offset(40f, 60f))
		state.move(Offset(300f, 400f))
		state.cancel()

		assertEquals(SpaceWorkbenchDragPhase.SETTLING, state.phase)
		assertEquals(Offset(40f, 60f), state.proxyPosition)
		assertEquals(SpaceWorkbenchDropOutcome.Dismiss, state.completeSettling())
	}

	@Test
	fun `dropping on active space settles then dismisses`() {
		val state = SpaceWorkbenchGestureState()
		state.start(Offset(100f, 100f))
		state.updateHoveredSpace(BuiltInSpaces.Manga, Offset(200f, 200f))

		state.release(BuiltInSpaces.Manga)
		assertEquals(SpaceWorkbenchDropOutcome.Dismiss, state.completeSettling())
	}

	@Test
	fun `workbench layout adapts to compact landscape and expanded windows`() {
		assertEquals(
			SpaceWorkbenchLayoutSpec(railWidth = 132.dp, coverHeight = 92.dp, showCovers = true),
			resolveSpaceWorkbenchLayoutSpec(availableWidth = 400.dp, availableHeight = 800.dp),
		)
		assertEquals(
			SpaceWorkbenchLayoutSpec(railWidth = 188.dp, coverHeight = 108.dp, showCovers = false),
			resolveSpaceWorkbenchLayoutSpec(availableWidth = 700.dp, availableHeight = 400.dp),
		)
		assertEquals(
			SpaceWorkbenchLayoutSpec(railWidth = 240.dp, coverHeight = 128.dp, showCovers = true),
			resolveSpaceWorkbenchLayoutSpec(availableWidth = 1000.dp, availableHeight = 800.dp),
		)
	}

	@Test
	fun `cockpit scale preserves useful rails across window shapes`() {
		val portrait = resolveSpaceCockpitLayoutSpec(360.dp, 800.dp)
		val landscape = resolveSpaceCockpitLayoutSpec(800.dp, 360.dp)
		val expanded = resolveSpaceCockpitLayoutSpec(1000.dp, 800.dp)

		assertEquals(0.82f, portrait.workspaceScale)
		assertFalse(portrait.isLandscape)
		assertEquals(0.80f, landscape.workspaceScale)
		assertTrue(landscape.isLandscape)
		assertEquals(0.88f, expanded.workspaceScale)
		assertTrue(expanded.isLandscape)
	}

	@Test
	fun `cockpit page context gives embedded reader highest priority`() {
		assertEquals(
			CockpitPageContext.MANGA_READER,
			resolveCockpitPageContext(
				hasEmbeddedMangaReader = true,
				isDetailsRoute = true,
				isContentListRoute = false,
			),
		)
		assertEquals(
			CockpitPageContext.DETAILS,
			resolveCockpitPageContext(false, isDetailsRoute = true, isContentListRoute = false),
		)
		assertEquals(
			CockpitPageContext.CONTENT_LIST,
			resolveCockpitPageContext(false, isDetailsRoute = false, isContentListRoute = true),
		)
		assertEquals(
			CockpitPageContext.MAIN,
			resolveCockpitPageContext(false, isDetailsRoute = false, isContentListRoute = false),
		)
	}

	@Test
	fun `cockpit momentum favors resumable works with unread updates`() {
		val dormant = resolveCockpitMomentumScore(canResume = false, newChapters = 10)
		val resumable = resolveCockpitMomentumScore(canResume = true, newChapters = 0)
		val advancing = resolveCockpitMomentumScore(canResume = true, newChapters = 3)

		assertTrue(resumable > dormant)
		assertTrue(advancing > resumable)
		assertEquals(180, resolveCockpitMomentumScore(canResume = true, newChapters = 100))
	}

	@Test
	fun `cockpit shelf key distinguishes works in the same space`() {
		assertNotEquals(
			cockpitShelfContentKey(BuiltInSpaces.Manga, "source", 1L),
			cockpitShelfContentKey(BuiltInSpaces.Manga, "source", 2L),
		)
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
