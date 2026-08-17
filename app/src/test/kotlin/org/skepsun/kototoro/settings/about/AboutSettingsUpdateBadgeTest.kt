package org.skepsun.kototoro.settings.about

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.github.AppVersion

class AboutSettingsUpdateBadgeTest {

	@Test
	fun `update badge is hidden when no update is available`() {
		assertFalse(shouldShowUpdateBadge(null))
	}

	@Test
	fun `update badge is visible when an update is available`() {
		val update = AppVersion(
			id = 1L,
			name = "1.9.9",
			url = "https://example.test/releases/1.9.9",
			apkSize = 1L,
			apkUrl = "https://example.test/app.apk",
			description = "",
		)

		assertTrue(shouldShowUpdateBadge(update))
	}
}
