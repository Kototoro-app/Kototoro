package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SpaceMotionTest {

	@Test
	fun `full motion is used by default`() {
		SpaceMotion.resolveMode(
			reducedVisualEffects = false,
			animatorDurationScale = 1f,
		) shouldBe SpaceMotionMode.FULL
	}

	@Test
	fun `reduced effects remove scale motion`() {
		SpaceMotion.resolveMode(
			reducedVisualEffects = true,
			animatorDurationScale = 1f,
		) shouldBe SpaceMotionMode.REDUCED
	}

	@Test
	fun `zero system animation scale disables transition`() {
		SpaceMotion.resolveMode(
			reducedVisualEffects = false,
			animatorDurationScale = 0f,
		) shouldBe SpaceMotionMode.DISABLED
	}
}
