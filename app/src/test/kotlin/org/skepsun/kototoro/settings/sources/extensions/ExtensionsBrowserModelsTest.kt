package org.skepsun.kototoro.settings.sources.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.extensions.install.ExtensionInstallDownloadState
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension

class ExtensionsBrowserModelsTest : FunSpec({

	test("buildExtensionsBrowserItems groups updates untrusted incompatible installed and available entries") {
		val items = buildExtensionsBrowserItems(
			type = ExternalExtensionType.MIHON,
			installed = listOf(
				installedEntry("pkg.update"),
				installedEntry("pkg.untrusted"),
				installedEntry("pkg.installed"),
			),
			available = listOf(
				availableExtension("pkg.update", "Update Source", versionName = "1.3.0", versionCode = 2),
				availableExtension("pkg.untrusted", "Untrusted Source", versionName = "1.3.0", versionCode = 2),
				availableExtension("pkg.incompatible", "Old Source", versionName = "1.1.0", isCompatible = false),
				availableExtension("pkg.available", "New Source"),
			),
			downloadStates = mapOf(
				"pkg.update" to ExtensionInstallDownloadState("pkg.update", bytesRead = 50L, contentLength = 100L),
			),
			selectedExtensionLanguages = setOf("en"),
			collapsedLanguageGroups = emptySet(),
			query = "",
			isTrustedPackage = { packageName, _ -> packageName != "pkg.untrusted" },
		)

		items.filterIsInstance<ExtensionsBrowserListItem.SectionHeader>().map { it.section } shouldContainExactly listOf(
			ExtensionsBrowserSection.UPDATES,
			ExtensionsBrowserSection.UNTRUSTED,
			ExtensionsBrowserSection.INCOMPATIBLE,
			ExtensionsBrowserSection.INSTALLED,
			ExtensionsBrowserSection.AVAILABLE,
		)

		val entries = items.filterIsInstance<ExtensionsBrowserListItem.Entry>()
		entries shouldHaveSize 5
		entries[0].pkgName shouldBe "pkg.update"
		entries[0].state shouldBe ExtensionsBrowserEntryState.INSTALLING
		entries[0].installProgressPercent shouldBe 50
		entries[1].pkgName shouldBe "pkg.untrusted"
		entries[1].state shouldBe ExtensionsBrowserEntryState.UNTRUSTED
		entries[2].pkgName shouldBe "pkg.incompatible"
		entries[2].state shouldBe ExtensionsBrowserEntryState.INCOMPATIBLE
		entries[3].pkgName shouldBe "pkg.installed"
		entries[3].state shouldBe ExtensionsBrowserEntryState.INSTALLED
		entries[4].pkgName shouldBe "pkg.available"
		entries[4].state shouldBe ExtensionsBrowserEntryState.AVAILABLE
	}

	test("ireader installed entry uses normalized package name for trust checks") {
		var validatedPackageName: String? = null

		val items = buildExtensionsBrowserItems(
			type = ExternalExtensionType.IREADER,
			installed = listOf(
				installedEntry(
					packageName = "ireader.novelfire.en",
					name = "novelfire",
				),
			),
			available = listOf(
				availableExtension(
					packageName = "ireader-en-novelfire",
					name = "novelfire (en)",
					type = ExternalExtensionType.IREADER,
				),
			),
			downloadStates = emptyMap(),
			selectedExtensionLanguages = setOf("en"),
			collapsedLanguageGroups = emptySet(),
			query = "",
			isTrustedPackage = { packageName, _ ->
				validatedPackageName = packageName
				packageName == "ireader.novelfire.en"
			},
		)

		validatedPackageName shouldBe "ireader.novelfire.en"
		items.filterIsInstance<ExtensionsBrowserListItem.Entry>().map { it.pkgName } shouldContainExactly listOf(
			"ireader.novelfire.en",
		)
		items.filterIsInstance<ExtensionsBrowserListItem.Entry>().single().state shouldBe ExtensionsBrowserEntryState.INSTALLED
	}

	test("ireader untrusted entry does not leave a duplicate installed card behind") {
		val items = buildExtensionsBrowserItems(
			type = ExternalExtensionType.IREADER,
			installed = listOf(
				installedEntry(
					packageName = "ireader.novelfire.en",
					name = "novelfire",
				),
			),
			available = listOf(
				availableExtension(
					packageName = "ireader-en-novelfire",
					name = "novelfire (en)",
					type = ExternalExtensionType.IREADER,
				),
			),
			downloadStates = emptyMap(),
			selectedExtensionLanguages = setOf("en"),
			collapsedLanguageGroups = emptySet(),
			query = "",
			isTrustedPackage = { _, _ -> false },
		)

		val entries = items.filterIsInstance<ExtensionsBrowserListItem.Entry>()
		entries shouldHaveSize 1
		entries.single().pkgName shouldBe "ireader-en-novelfire"
		entries.single().state shouldBe ExtensionsBrowserEntryState.UNTRUSTED
	}

	test("same versionCode with newer extension lib is still installed") {
		val items = buildExtensionsBrowserItems(
			type = ExternalExtensionType.MIHON,
			installed = listOf(
				installedEntry(
					packageName = "pkg.same",
					libVersion = 1.4,
				),
			),
			available = listOf(
				availableExtension(
					packageName = "pkg.same",
					name = "Same Source",
					versionCode = 1L,
					libVersion = 1.6,
				),
			),
			downloadStates = emptyMap(),
			selectedExtensionLanguages = setOf("en"),
			collapsedLanguageGroups = emptySet(),
			query = "",
			isTrustedPackage = { _, _ -> true },
		)

		val entry = items.filterIsInstance<ExtensionsBrowserListItem.Entry>().single()
		entry.state shouldBe ExtensionsBrowserEntryState.INSTALLED
	}

	test("normalizeExtensionLanguageCode maps all to multi-language bucket") {
		"all".normalizeExtensionLanguageCode() shouldBe ""
		"ALL".normalizeExtensionLanguageCode() shouldBe ""
		"multi language".normalizeExtensionLanguageCode() shouldBe ""
		"多语言".normalizeExtensionLanguageCode() shouldBe ""
		"zh".normalizeExtensionLanguageCode() shouldBe "zh"
	}

	test("normalizeExtensionLanguageCode folds ireader and lnreader chinese aliases into zh") {
		"cn".normalizeExtensionLanguageCode() shouldBe "zh"
		"tw".normalizeExtensionLanguageCode() shouldBe "zh"
		"zh-CN".normalizeExtensionLanguageCode() shouldBe "zh"
		"zh_Hans".normalizeExtensionLanguageCode() shouldBe "zh"
		"zh-Hant/en".normalizeExtensionLanguageCode() shouldBe "zh"
		"cn,en".normalizeExtensionLanguageCode() shouldBe "zh"
		" 中文, 汉语, 漢語".normalizeExtensionLanguageCode() shouldBe "zh"
		"中文，汉语，漢語".normalizeExtensionLanguageCode() shouldBe "zh"
		"中文、汉语、漢語".normalizeExtensionLanguageCode() shouldBe "zh"
		"Chinese, ZHO, chi".normalizeExtensionLanguageCode() shouldBe "zh"
		"all,en".normalizeExtensionLanguageCode() shouldBe "en"
		"jp".normalizeExtensionLanguageCode() shouldBe "ja"
		"kr".normalizeExtensionLanguageCode() shouldBe "ko"
		"日本語，日本语".normalizeExtensionLanguageCode() shouldBe "ja"
		"한국어，조선말".normalizeExtensionLanguageCode() shouldBe "ko"
	}

	test("normalizeExtensionLanguageCode maps official lnreader display language labels to codes") {
		mapOf(
			"English" to "en",
			"Русский" to "ru",
			"Español" to "es",
			"Français" to "fr",
			"Português" to "pt",
			"Bahasa Indonesia" to "id",
			"Tiếng Việt" to "vi",
			"Türkçe" to "tr",
			"Українська" to "uk",
			"Polski" to "pl",
			"ไทย" to "th",
			"‎العربية" to "ar",
		).forEach { (raw, expected) ->
			raw.normalizeExtensionLanguageCode() shouldBe expected
		}
	}

	test("ireader available extension infers language from package when repo marks it as all") {
		availableExtension(
			packageName = "ireader-cn-yuedu",
			name = "Yuedu",
			type = ExternalExtensionType.IREADER,
			lang = "all",
		).normalizeExtensionLanguageCode() shouldBe "zh"
	}

	test("installed extension source languages prefer chinese over multi-language bucket") {
		listOf("all", "cn", "en").selectExtensionLanguageCode() shouldBe "zh"
		listOf("all", "en").selectExtensionLanguageCode() shouldBe "en"
		listOf("ja", "en").selectExtensionLanguageCode() shouldBe ""
	}
})

private fun installedEntry(
	packageName: String,
	name: String = packageName.substringAfter('.'),
	lang: String = "en",
	sourceNames: List<String> = listOf("Source $packageName"),
	libVersion: Double = 1.2,
): InstalledExtensionEntry {
	return InstalledExtensionEntry(
		pkgName = packageName,
		name = name,
		versionName = "1.2.0",
		versionCode = 1L,
		libVersion = libVersion,
		lang = lang,
		isNsfw = false,
		sourceNames = sourceNames,
	)
}

private fun availableExtension(
	packageName: String,
	name: String,
	type: ExternalExtensionType = ExternalExtensionType.MIHON,
	versionName: String = "1.2.0",
	versionCode: Long = 1L,
	isCompatible: Boolean = true,
	repoName: String = "Repo",
	lang: String = "en",
	sourceNames: List<String> = listOf(name),
	libVersion: Double = versionName.substringBeforeLast('.').toDouble(),
): RepoAvailableExtension {
	return RepoAvailableExtension(
		type = type,
		name = name,
		pkgName = packageName,
		versionName = versionName,
		versionCode = versionCode,
		libVersion = libVersion,
		lang = lang,
		isNsfw = false,
		sourceNames = sourceNames,
		archiveName = "$packageName.apk",
		archiveUrl = null,
		iconUrl = "",
		repoUrl = "https://example.org/repo",
		repoName = repoName,
		signatureHash = "aa",
		isCompatible = isCompatible,
	)
}
