package org.skepsun.kototoro.settings.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SpacesSettingsScreenTest {

	@Test
	fun `first explicit enable request shows onboarding`() {
		shouldShowSpaceOnboarding(
			requestedEnabled = true,
			hasSeenOnboarding = false,
		) shouldBe true
	}

	@Test
	fun `disable and repeat enable requests bypass onboarding`() {
		shouldShowSpaceOnboarding(
			requestedEnabled = false,
			hasSeenOnboarding = false,
		) shouldBe false
		shouldShowSpaceOnboarding(
			requestedEnabled = true,
			hasSeenOnboarding = true,
		) shouldBe false
	}
}
