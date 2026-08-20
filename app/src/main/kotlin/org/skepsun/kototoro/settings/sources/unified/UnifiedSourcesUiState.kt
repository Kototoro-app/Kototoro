package org.skepsun.kototoro.settings.sources.unified

import android.content.Intent
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.parsers.model.ContentType

sealed interface UnifiedSourcesEvent {
	data class Message(val message: String) : UnifiedSourcesEvent
	data class InstallFailed(val message: String) : UnifiedSourcesEvent
	data class ConfirmPackageInstall(
		val packageId: String,
		val kind: UnifiedSourceKind,
		val name: String,
		val sourceCount: Int,
		val mode: ExtensionInstallMode,
	) : UnifiedSourcesEvent
	data class TrustExternalRepository(val repo: ExternalExtensionRepo) : UnifiedSourcesEvent
	data class StartInstall(val intent: Intent) : UnifiedSourcesEvent
	data class StartUninstall(val intent: Intent) : UnifiedSourcesEvent
	data class PackageStateDetails(val item: UnifiedSourcePackageItem) : UnifiedSourcesEvent
}

data class UnifiedSourcesFilterState(
	val query: String = "",
	val kinds: Set<UnifiedSourceKind> = emptySet(),
	val contentTypes: Set<ContentType> = emptySet(),
	val languages: Set<String> = emptySet(),
	val locationTypes: Set<UnifiedRepositoryLocationType> = emptySet(),
	val enabledFilter: UnifiedEnabledFilter = UnifiedEnabledFilter.ALL,
	val availabilityFilter: UnifiedAvailabilityFilter = UnifiedAvailabilityFilter.AVAILABLE,
	val testAvailabilityFilter: UnifiedTestAvailabilityFilter = UnifiedTestAvailabilityFilter.ALL,
	val nsfwFilter: UnifiedNsfwFilter = UnifiedNsfwFilter.ALL,
)

enum class UnifiedEnabledFilter {
	ALL,
	ENABLED,
	DISABLED,
}

enum class UnifiedAvailabilityFilter {
	ALL,
	AVAILABLE,
	UNAVAILABLE,
}

enum class UnifiedTestAvailabilityFilter {
	ALL,
	UNTESTED,
	AVAILABLE,
	UNAVAILABLE,
}

enum class UnifiedNsfwFilter {
	ALL,
	SFW,
	NSFW,
}

sealed interface UnifiedSourcesUiState {
	data object Loading : UnifiedSourcesUiState

	data class Ready(
		val filters: UnifiedSourcesFilterState,
		val repositories: List<UnifiedSourceRepositoryItem>,
		val packages: List<UnifiedSourcePackageItem>,
		val sources: List<UnifiedSourceItem>,
		val allRepositories: List<UnifiedSourceRepositoryItem>,
		val allPackages: List<UnifiedSourcePackageItem>,
		val allSources: List<UnifiedSourceItem>,
		val availableKinds: List<UnifiedSourceKind>,
		val availableContentTypes: List<ContentType>,
		val availableLocationTypes: List<UnifiedRepositoryLocationType>,
		val availableLanguages: List<String>,
	) : UnifiedSourcesUiState
}

