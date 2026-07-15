package org.skepsun.kototoro.space.ui

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces

class SpaceSwitcherDelegateTest {

	@Test
	fun `ordinary immersive switch does not request resume`() {
		resumeSpaceExtraValue(BuiltInSpaces.Novel, resumeReading = false).shouldBeNull()
	}

	@Test
	fun `continue action requests resume for target space`() {
		resumeSpaceExtraValue(BuiltInSpaces.Anime, resumeReading = true) shouldBe BuiltInSpaces.Anime.value
	}

	@Test
	fun `resumed immersive session restores its own space`() {
		shouldRestoreImmersiveSpaceOnResume(
			sessionSpaceId = BuiltInSpaces.Novel,
			activeSpaceId = BuiltInSpaces.Manga,
			immersiveSwitchEnabled = true,
			switchInProgress = false,
			transitionSuppressionTarget = null,
		) shouldBe true
	}

	@Test
	fun `active switch and pending activity restoration do not race resume synchronization`() {
		shouldRestoreImmersiveSpaceOnResume(
			sessionSpaceId = BuiltInSpaces.Novel,
			activeSpaceId = BuiltInSpaces.Manga,
			immersiveSwitchEnabled = true,
			switchInProgress = true,
			transitionSuppressionTarget = null,
		) shouldBe false

		shouldRestoreImmersiveSpaceOnResume(
			sessionSpaceId = BuiltInSpaces.Novel,
			activeSpaceId = BuiltInSpaces.Manga,
			immersiveSwitchEnabled = true,
			switchInProgress = false,
			transitionSuppressionTarget = BuiltInSpaces.Novel,
		) shouldBe false
	}
}
