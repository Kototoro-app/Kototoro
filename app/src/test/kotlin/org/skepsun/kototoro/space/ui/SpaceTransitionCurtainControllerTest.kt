package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces

class SpaceTransitionCurtainControllerTest {

	@Test
	fun `only active target host can reveal curtain`() {
		isSpaceCurtainRevealHost(
			targetSpaceId = BuiltInSpaces.Novel,
			hostSpaceId = BuiltInSpaces.Manga,
			activeSpaceId = BuiltInSpaces.Novel,
		) shouldBe false
		isSpaceCurtainRevealHost(
			targetSpaceId = BuiltInSpaces.Novel,
			hostSpaceId = BuiltInSpaces.Novel,
			activeSpaceId = BuiltInSpaces.Novel,
		) shouldBe true
	}

	@Test
	fun `transition follows actual curtain completion`() = runTest {
		val controller = SpaceTransitionCurtainController()
		val covering = async {
			controller.cover(BuiltInSpaces.Manga, BuiltInSpaces.Novel, animated = true)
		}

		runCurrent()
		controller.state.value.phase shouldBe SpaceTransitionPhase.COVERING
		controller.markCovered(BuiltInSpaces.Novel)
		runCurrent()
		covering.await() shouldBe true
		controller.state.value.phase shouldBe SpaceTransitionPhase.COVERED

		val revealing = async { controller.reveal(BuiltInSpaces.Novel) }
		runCurrent()
		controller.state.value.phase shouldBe SpaceTransitionPhase.REVEALING
		controller.markRevealFinished(BuiltInSpaces.Novel)
		runCurrent()
		revealing.await()
		controller.state.value shouldBe SpaceTransitionState()
	}

	@Test
	fun `disabled animation still waits for its drawn frame`() = runTest {
		val controller = SpaceTransitionCurtainController()
		val covering = async {
			controller.cover(BuiltInSpaces.Manga, BuiltInSpaces.Anime, animated = false)
		}

		runCurrent()
		controller.state.value.phase shouldBe SpaceTransitionPhase.COVERING
		controller.markCovered(BuiltInSpaces.Anime)
		runCurrent()

		covering.await() shouldBe true
		controller.state.value.phase shouldBe SpaceTransitionPhase.COVERED
	}

	@Test
	fun `immersive handoff can keep target curtain hidden`() = runTest {
		val controller = SpaceTransitionCurtainController()
		val covering = async {
			controller.cover(
				from = BuiltInSpaces.Manga,
				target = BuiltInSpaces.Anime,
				animated = false,
				showOnTarget = false,
			)
		}

		runCurrent()
		controller.state.value.showOnTarget shouldBe false
		controller.markCovered(BuiltInSpaces.Anime)
		runCurrent()
		covering.await() shouldBe true
	}

	@Test
	fun `same space and overlapping transitions are ignored`() = runTest {
		val controller = SpaceTransitionCurtainController()

		controller.cover(BuiltInSpaces.Manga, BuiltInSpaces.Manga, animated = false) shouldBe false
		val covering = async {
			controller.cover(BuiltInSpaces.Manga, BuiltInSpaces.Novel, animated = false)
		}
		runCurrent()
		controller.cover(BuiltInSpaces.Novel, BuiltInSpaces.Anime, animated = false) shouldBe false
		controller.markCovered(BuiltInSpaces.Novel)
		runCurrent()
		covering.await() shouldBe true
	}
}
