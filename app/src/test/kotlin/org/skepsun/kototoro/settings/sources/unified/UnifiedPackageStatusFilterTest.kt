package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class UnifiedPackageStatusFilterTest : FunSpec({

	test("packageUpdateCount counts packages with UPDATE_AVAILABLE state") {
		val packages = listOf(
			testPackage(id = "p1", isInstalled = true, state = UnifiedSourcePackageState.INSTALLED),
			testPackage(id = "p2", isInstalled = true, state = UnifiedSourcePackageState.UPDATE_AVAILABLE),
			testPackage(id = "p3", isInstalled = false, state = UnifiedSourcePackageState.AVAILABLE),
			testPackage(id = "p4", isInstalled = true, state = UnifiedSourcePackageState.UPDATE_AVAILABLE),
		)

		val ready = UnifiedSourcesUiState.Ready(
			filters = UnifiedSourcesFilterState(),
			repositories = emptyList(),
			packages = packages,
			sources = emptyList(),
			allRepositories = emptyList(),
			allPackages = packages,
			allSources = emptyList(),
			availableKinds = emptyList(),
			availableContentTypes = emptyList(),
			availableLocationTypes = emptyList(),
			availableLanguages = emptyList(),
		)

		ready.packageUpdateCount shouldBe 2
	}

	test("filterBy filters according to packageStatusFilter") {
		val installed = testPackage(id = "p1", isInstalled = true, state = UnifiedSourcePackageState.INSTALLED)
		val updateAvailable = testPackage(id = "p2", isInstalled = true, state = UnifiedSourcePackageState.UPDATE_AVAILABLE)
		val notInstalled = testPackage(id = "p3", isInstalled = false, state = UnifiedSourcePackageState.AVAILABLE)
		val list = listOf(installed, updateAvailable, notInstalled)

		fun filterWith(statusFilter: UnifiedPackageStatusFilter): List<UnifiedSourcePackageItem> {
			return list.filter {
				when (statusFilter) {
					UnifiedPackageStatusFilter.ALL -> true
					UnifiedPackageStatusFilter.UPDATE_AVAILABLE -> it.state == UnifiedSourcePackageState.UPDATE_AVAILABLE
					UnifiedPackageStatusFilter.INSTALLED -> it.isInstalled
					UnifiedPackageStatusFilter.NOT_INSTALLED -> !it.isInstalled
				}
			}
		}

		filterWith(UnifiedPackageStatusFilter.ALL) shouldContainExactly listOf(installed, updateAvailable, notInstalled)
		filterWith(UnifiedPackageStatusFilter.UPDATE_AVAILABLE) shouldContainExactly listOf(updateAvailable)
		filterWith(UnifiedPackageStatusFilter.INSTALLED) shouldContainExactly listOf(installed, updateAvailable)
		filterWith(UnifiedPackageStatusFilter.NOT_INSTALLED) shouldContainExactly listOf(notInstalled)
	}

	test("default filter state has packageStatusFilter as ALL") {
		val filterState = UnifiedSourcesFilterState()
		filterState.packageStatusFilter shouldBe UnifiedPackageStatusFilter.ALL
	}
})

private fun testPackage(
	id: String,
	isInstalled: Boolean,
	state: UnifiedSourcePackageState,
): UnifiedSourcePackageItem {
	return UnifiedSourcePackageItem(
		id = id,
		kind = UnifiedSourceKind.MIHON,
		name = "Package $id",
		packageName = "org.example.$id",
		repositoryId = null,
		repositoryName = null,
		versionName = "1.0",
		versionCode = 1,
		language = null,
		isInstalled = isInstalled,
		isNsfw = false,
		sourceCount = 1,
		sourceNames = listOf("Source $id"),
		state = state,
	)
}
