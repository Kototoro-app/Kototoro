package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.mockk.every
import io.mockk.mockk
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class UnifiedSourcesRefreshStateTest : FunSpec({

	test("package refresh snapshots only disabled sources") {
		val enabled = source("enabled")
		val disabled = source("disabled")
		val state = readyState(
			listOf(
				sourceItem(enabled, isEnabled = true),
				sourceItem(disabled, isEnabled = false),
			),
		)

		state.disabledSourcesForPackageRefresh() shouldContainExactly listOf(disabled)
	}

	test("package refresh has no source snapshot before catalog is ready") {
		UnifiedSourcesUiState.Loading.disabledSourcesForPackageRefresh().shouldBeEmpty()
	}
})

private fun source(name: String): ContentSource = mockk {
	every { this@mockk.name } returns name
}

private fun sourceItem(source: ContentSource, isEnabled: Boolean): UnifiedSourceItem {
	return UnifiedSourceItem(
		id = source.name,
		kind = UnifiedSourceKind.MIHON,
		source = source,
		title = source.name,
		language = "en",
		contentType = ContentType.MANGA,
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

private fun readyState(sources: List<UnifiedSourceItem>): UnifiedSourcesUiState.Ready {
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
