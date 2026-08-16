package org.skepsun.kototoro.extensions.install

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SystemPackageInstallerTest {

	@Test
	fun `installed version must reach requested version`() {
		isInstalledVersionSatisfied(installedVersion = null, expectedVersion = 12L) shouldBe false
		isInstalledVersionSatisfied(installedVersion = 11L, expectedVersion = 12L) shouldBe false
		isInstalledVersionSatisfied(installedVersion = 12L, expectedVersion = 12L) shouldBe true
		isInstalledVersionSatisfied(installedVersion = 13L, expectedVersion = 12L) shouldBe true
	}
}
