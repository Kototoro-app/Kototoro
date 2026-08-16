package org.skepsun.kototoro.extensions.repo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExternalExtensionPackageNameTest {

	@Test
	fun `mihon and aniyomi package names remain unchanged`() {
		ExternalExtensionType.MIHON.toInstalledPackageName("eu.kanade.tachiyomi.extension.en.example") shouldBe
			"eu.kanade.tachiyomi.extension.en.example"
		ExternalExtensionType.ANIYOMI.toInstalledPackageName("eu.kanade.tachiyomi.animeextension.en.example") shouldBe
			"eu.kanade.tachiyomi.animeextension.en.example"
	}

	@Test
	fun `ireader repository package name maps to installed package name`() {
		ExternalExtensionType.IREADER.toInstalledPackageName("ireader-en-novel-example") shouldBe
			"ireader.novel-example.en"
	}

	@Test
	fun `already normalized ireader package name remains unchanged`() {
		ExternalExtensionType.IREADER.toInstalledPackageName("ireader.novel-example.en") shouldBe
			"ireader.novel-example.en"
	}
}
