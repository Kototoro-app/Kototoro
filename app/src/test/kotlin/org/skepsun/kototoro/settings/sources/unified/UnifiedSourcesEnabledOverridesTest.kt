package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class UnifiedSourcesEnabledOverridesTest : FunSpec({

	test("enable override flips a disabled item in both visible and all lists") {
		val item = enabledOverrideSourceItem(id = "TSUNDOKU_1", isEnabled = false)
		val state = enabledOverrideReadyState(listOf(item))

		val merged = state.withEnabledOverrides(mapOf("TSUNDOKU_1" to true))

		merged.sources.single().isEnabled shouldBe true
		merged.allSources.single().isEnabled shouldBe true
	}

	test("disable override flips an enabled item") {
		val item = enabledOverrideSourceItem(id = "TSUNDOKU_1", isEnabled = true)
		val state = enabledOverrideReadyState(listOf(item))

		val merged = state.withEnabledOverrides(mapOf("TSUNDOKU_1" to false))

		merged.sources.single().isEnabled shouldBe false
		merged.allSources.single().isEnabled shouldBe false
	}

	test("override matching authoritative state is a no-op at the item level") {
		val item = enabledOverrideSourceItem(id = "TSUNDOKU_1", isEnabled = true)

		val merged = listOf(item).withEnabledOverrides(mapOf("TSUNDOKU_1" to true))

		merged.single() shouldBeSameInstanceAs item
	}

	test("override for an unrelated id leaves every item untouched") {
		val item = enabledOverrideSourceItem(id = "MIHON_7", isEnabled = false)
		val state = enabledOverrideReadyState(listOf(item))

		val merged = state.withEnabledOverrides(mapOf("OTHER_SOURCE" to true))

		merged.sources.single() shouldBeSameInstanceAs item
		merged.allSources.single() shouldBeSameInstanceAs item
	}

	test("empty overrides returns the same state instance") {
		val state = enabledOverrideReadyState(listOf(enabledOverrideSourceItem(id = "TSUNDOKU_1", isEnabled = true)))

		state.withEnabledOverrides(emptyMap()) shouldBeSameInstanceAs state
		state.withEnabledOverrides(mapOf()) shouldBeSameInstanceAs state
	}
})

private class EnabledOverrideSource(override val name: String) : ContentSource {
	override val locale: String = ""
	override val contentType: ContentType = ContentType.NOVEL
}

private fun enabledOverrideSourceItem(
	id: String,
	isEnabled: Boolean,
): UnifiedSourceItem {
	return UnifiedSourceItem(
		id = id,
		kind = UnifiedSourceKind.TSUNDOKU,
		source = EnabledOverrideSource(id),
		title = id,
		language = null,
		contentType = ContentType.NOVEL,
		repositoryId = null,
		repositoryName = null,
		packageId = null,
		packageName = null,
		isEnabled = isEnabled,
		isPinned = false,
		isAvailable = true,
		isInstalled = true,
		isNsfw = false,
		isBroken = false,
	)
}

private fun enabledOverrideReadyState(sources: List<UnifiedSourceItem>): UnifiedSourcesUiState.Ready {
	return UnifiedSourcesUiState.Ready(
		filters = UnifiedSourcesFilterState(),
		repositories = emptyList(),
		packages = emptyList(),
		sources = sources,
		allRepositories = emptyList(),
		allPackages = emptyList(),
		allSources = sources,
		availableKinds = emptyList(),
		availableContentTypes = emptyList(),
		availableLocationTypes = emptyList(),
		availableLanguages = emptyList(),
	)
}
