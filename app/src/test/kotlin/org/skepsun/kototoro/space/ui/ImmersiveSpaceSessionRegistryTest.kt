package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces

class ImmersiveSpaceSessionRegistryTest {

	@Test
	fun `suppression is consumed only by its target space`() {
		val registry = ImmersiveSpaceSessionRegistry()

		registry.suppressMainTransitionTo(BuiltInSpaces.Novel)
		registry.completeMainTransitionSuppression(BuiltInSpaces.Manga)

		registry.mainTransitionSuppressionTarget.value shouldBe BuiltInSpaces.Novel

		registry.completeMainTransitionSuppression(BuiltInSpaces.Novel)

		registry.mainTransitionSuppressionTarget.value shouldBe null
	}
}
