package org.skepsun.kototoro.space.data

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

class DefaultSpaceSessionValidatorTest {

	@Test
	fun `unknown top level key is dropped`() = runTest {
		val validator = DefaultSpaceSessionValidator()
		val snapshot = snapshot(
			routes = listOf(
				SpaceRouteSnapshot.TopLevel("home"),
				SpaceRouteSnapshot.ContentList("AVAILABLE"),
			),
			stacks = mapOf(
				"home" to listOf(SpaceRouteSnapshot.TopLevel("home")),
				"not_a_top_level" to listOf(SpaceRouteSnapshot.TopLevel("not_a_top_level")),
			),
		)

		val validated = validator.validate(snapshot)

		validated.stacks shouldContainExactly mapOf(
			"home" to listOf(SpaceRouteSnapshot.TopLevel("home")),
		)
	}

	@Test
	fun `work details route is kept as saved`() = runTest {
		val validator = DefaultSpaceSessionValidator()
		val snapshot = snapshot(
			routes = listOf(
				SpaceRouteSnapshot.TopLevel("home"),
				SpaceRouteSnapshot.WorkDetails(42L, 99L),
			),
		)

		val validated = validator.validate(snapshot)

		validated.stacks.getValue("home").last() shouldBe SpaceRouteSnapshot.WorkDetails(42L, 99L)
	}

	@Test
	fun `temporarily unavailable source keeps content list route for cold start restoration`() = runTest {
		val validator = DefaultSpaceSessionValidator()
		val snapshot = snapshot(
			routes = listOf(
				SpaceRouteSnapshot.TopLevel("home"),
				SpaceRouteSnapshot.ContentList("REMOVED"),
			),
		)

		val validated = validator.validate(snapshot)

		validated.stacks.getValue("home") shouldBe listOf(
			SpaceRouteSnapshot.TopLevel("home"),
			SpaceRouteSnapshot.ContentList("REMOVED"),
		)
	}

	private fun snapshot(
		routes: List<SpaceRouteSnapshot>,
		stacks: Map<String, List<SpaceRouteSnapshot>> = mapOf("home" to routes),
	) = SpaceSessionSnapshot(
		spaceId = BuiltInSpaces.Manga,
		selectedTopLevel = "home",
		resumeRoute = routes.lastOrNull(),
		stacks = stacks,
		lastAccessed = 1L,
		updatedAt = 1L,
	)
}
