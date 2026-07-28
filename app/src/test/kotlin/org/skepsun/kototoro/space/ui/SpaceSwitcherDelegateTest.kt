package org.skepsun.kototoro.space.ui

import android.content.Intent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces

class SpaceSwitcherDelegateTest {

	@Test
	fun `returning to main preserves immersive reader activities`() {
		mainReturnActivityFlags() shouldBe (
			Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
				Intent.FLAG_ACTIVITY_SINGLE_TOP or
				Intent.FLAG_ACTIVITY_NO_ANIMATION
		)
	}

	@Test
	fun `ordinary immersive switch does not request resume`() {
		resumeSpaceExtraValue(BuiltInSpaces.Novel, resumeReading = false).shouldBeNull()
	}

	@Test
	fun `continue action requests resume for target space`() {
		resumeSpaceExtraValue(BuiltInSpaces.Anime, resumeReading = true) shouldBe BuiltInSpaces.Anime.value
	}

	@Test
	fun `immersive activity keeps its explicit space identity after recreation`() {
		immersiveSessionSpaceId(BuiltInSpaces.Novel.value, BuiltInSpaces.Manga) shouldBe BuiltInSpaces.Novel
	}

	@Test
	fun `legacy immersive activity falls back to active space`() {
		immersiveSessionSpaceId(null, BuiltInSpaces.Manga) shouldBe BuiltInSpaces.Manga
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
