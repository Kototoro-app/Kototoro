package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.core.lnreader.LNReaderPluginInfo

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

	test("same LNReader source from multiple repositories selects highest version") {
		val older = lnReaderAvailablePlugin(version = "1.9.9", repoUrl = "https://first.example/index.json")
		val newer = lnReaderAvailablePlugin(version = "v1.10.0", repoUrl = "https://second.example/index.json")

		listOf(older, newer).withPreferredLnReaderVersions() shouldBe listOf(newer)
	}

	test("same LNReader source and version preserves configured repository order") {
		val first = lnReaderAvailablePlugin(version = "2.0.0", repoUrl = "https://first.example/index.json")
		val second = lnReaderAvailablePlugin(version = "2.0.0", repoUrl = "https://second.example/index.json")

		listOf(first, second).withPreferredLnReaderVersions() shouldBe listOf(first)
	}

	test("LNReader update policy compares installed semantic version") {
		isNewerLnReaderVersion(candidate = "1.1.20", installed = "1.1.9") shouldBe true
		isNewerLnReaderVersion(candidate = "1.1.9", installed = "1.1.20") shouldBe false
		isNewerLnReaderVersion(candidate = "1.1.20", installed = null) shouldBe false
	}
})

private fun lnReaderAvailablePlugin(
	version: String,
	repoUrl: String,
): LnReaderAvailablePlugin {
	return LnReaderAvailablePlugin(
		plugin = LNReaderPluginInfo(
			id = "shared-source",
			name = "Shared source",
			site = "https://shared.example",
			lang = "en",
			version = version,
			url = "$repoUrl/shared-source.js",
			iconUrl = "",
		),
		repoUrl = repoUrl,
		repoName = repoUrl,
	)
}

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
