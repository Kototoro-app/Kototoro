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
}
