package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class UnifiedSourcePackageIdentityTest : FunSpec({

	test("duplicate catalog entries matching one installed LNReader package collapse to one item") {
		val installedId = "package:LNREADER:JSON_LNREADER_331E08A0"
		val firstRepositoryMatch = packageItem(
			id = installedId,
			repositoryId = "repo:LNREADER:https://first.example/index.json",
		)
		val secondRepositoryMatch = packageItem(
			id = installedId,
			repositoryId = "repo:LNREADER:https://second.example/index.json",
		)

		val result = listOf(firstRepositoryMatch, secondRepositoryMatch).withUniquePackageIds()

		result shouldHaveSize 1
		result.single() shouldBe firstRepositoryMatch
	}

	test("package identity guard preserves source order for distinct packages") {
		val first = packageItem(id = "package:LNREADER:first")
		val second = packageItem(id = "package:LNREADER:second")

		listOf(first, second).withUniquePackageIds() shouldBe listOf(first, second)
	}
})

private fun packageItem(
	id: String,
	repositoryId: String? = null,
): UnifiedSourcePackageItem {
	return UnifiedSourcePackageItem(
		id = id,
		kind = UnifiedSourceKind.LNREADER,
		name = id,
		packageName = id.substringAfterLast(':'),
		repositoryId = repositoryId,
		repositoryName = null,
		versionName = null,
		versionCode = null,
		language = null,
		isInstalled = true,
		isNsfw = false,
		sourceCount = 1,
		sourceNames = listOf(id),
	)
}
