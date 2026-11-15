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

    /**
     * A pasted address was recognised as [kind]; the screen continues down the same add path the manual choice uses,
     * so the third-party notice and the install policy still apply.
     */
    data class SourceAddressResolved(
        val kind: UnifiedSourceKind,
        val url: String,
        val title: String?,
        /** Entries the body declares, so the notice can say what is about to be added. */
        val previewCount: Int = 0,
        val previewNames: List<String> = emptyList(),
    ) : UnifiedSourcesEvent

    /** The body named no known format, so the screen reopens the kind list rather than leaving the user to retry. */
    data object SourceAddressUnrecognised : UnifiedSourcesEvent

    /**
     * Sources just landed in the list, so the screen can offer to browse them instead of ending the flow on a toast.
     * The ids are the ones the source list itself uses, so the match cannot drift.
     */
    /** A recognised add failed, so the screen can retry it or fall back to picking the type by hand. */
    data class SourceAddFailed(
        val kind: UnifiedSourceKind,
        val url: String,
        val title: String?,
        val message: String,
    ) : UnifiedSourcesEvent

    data class SourceImportCompleted(
        val kind: UnifiedSourceKind,
        val sourceIds: List<String>,
        val sourceCount: Int,
    ) : UnifiedSourcesEvent
}

data class UnifiedSourcesFilterState(
    val query: String = "",
    val kinds: Set<UnifiedSourceKind> = emptySet(),
    val contentTypes: Set<ContentType> = emptySet(),
    val repositoryId: String? = null,
    val languages: Set<String> = emptySet(),
    val isStrictLanguageFilter: Boolean = false,
    val locationTypes: Set<UnifiedRepositoryLocationType> = emptySet(),
    val enabledFilter: UnifiedEnabledFilter = UnifiedEnabledFilter.ALL,
    val availabilityFilter: UnifiedAvailabilityFilter = UnifiedAvailabilityFilter.AVAILABLE,
    val testAvailabilityFilter: UnifiedTestAvailabilityFilter = UnifiedTestAvailabilityFilter.ALL,
    val nsfwFilter: UnifiedNsfwFilter = UnifiedNsfwFilter.ALL,
    val lockFilter: UnifiedLockFilter = UnifiedLockFilter.ALL,
    val packageStatusFilter: UnifiedPackageStatusFilter = UnifiedPackageStatusFilter.ALL,
)

enum class UnifiedPackageStatusFilter {
    ALL,
    UPDATE_AVAILABLE,
    INSTALLED,
    NOT_INSTALLED,
}

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
    CLOUDFLARE,
}

enum class UnifiedNsfwFilter {
    ALL,
    SFW,
    NSFW,
}

enum class UnifiedLockFilter {
    ALL,
    LOCKED,
    UNLOCKED,
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
        /** Store packages whose sources cover sources missing from imported backups. */
        val recommendedPackages: List<RecommendedPackageItem> = emptyList(),
        /** Missing sources (recorded at import time) that no configured repository covers. */
        val missingSourcesWithoutMatch: List<MissingSourceHint> = emptyList(),
        /** Suggested repositories that may carry the unmatched missing sources. */
        val suggestedRepositoriesForMissing: List<UnifiedRecommendedRepository> = emptyList(),
    ) : UnifiedSourcesUiState {
        val packageUpdateCount: Int
            get() = allPackages.count { it.state == UnifiedSourcePackageState.UPDATE_AVAILABLE }
    }
}

/**
 * A store package shown in the Recommended section together with the display names of the
 * missing imported sources it covers (sorted by coverage, best matches first).
 */
data class RecommendedPackageItem(
    val item: UnifiedSourcePackageItem,
    val coversMissingSources: List<String>,
)

/**
 * A source recorded in `source_origins` by an external backup import whose extension is
 * not installed and which no configured repository index covers.
 */
data class MissingSourceHint(
    val kind: UnifiedSourceKind,
    /** Persisted binding key, e.g. `MIHON_123456789`. */
    val sourceKey: String,
    /** Human-readable name from the backup's source registry, when known. */
    val displayName: String?,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: sourceKey
}
