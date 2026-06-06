package org.skepsun.kototoro.settings.sources.unified

import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.core.lnreader.LNReaderPluginInfo
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

enum class UnifiedSourceKind {
	NATIVE,
	JAR,
	CLOUDSTREAM,
	MIHON,
	ANIYOMI,
	IREADER,
	LEGADO,
	TVBOX,
	JS,
	LNREADER,
}

enum class UnifiedRepositoryCapability {
	REFRESH,
	VERSIONED_INDEX,
	INSTALL_PACKAGE,
	IMPORT_JSON_LIST,
	TRUST_FINGERPRINT,
}

enum class UnifiedRepositoryLocationType {
	REMOTE_URL,
	LOCAL_FILE,
	INLINE_IMPORT,
	PRESET_ONLY,
}

enum class UnifiedSourcePackageState {
	AVAILABLE,
	UPDATE_AVAILABLE,
	INSTALLED,
	INSTALLING,
	UNTRUSTED,
	INCOMPATIBLE,
}

enum class UnifiedSourcePackageInstallLocation {
	SYSTEM,
	LOCAL_APK,
}

data class UnifiedRecommendedRepository(
	val kind: UnifiedSourceKind,
	val name: String,
	val url: String,
	val locationType: UnifiedRepositoryLocationType,
	val capabilities: Set<UnifiedRepositoryCapability>,
	val note: String? = null,
)

data class UnifiedSourceRepositoryItem(
	val id: String,
	val kind: UnifiedSourceKind,
	val name: String,
	val url: String,
	val locationType: UnifiedRepositoryLocationType,
	val website: String,
	val isConfigured: Boolean,
	val isPreset: Boolean,
	val capabilities: Set<UnifiedRepositoryCapability>,
	val version: String? = null,
	val lastSuccessAt: Long? = null,
	val lastError: String? = null,
)

data class UnifiedSourcePackageItem(
	val id: String,
	val kind: UnifiedSourceKind,
	val name: String,
	val packageName: String?,
	val repositoryId: String?,
	val repositoryName: String?,
	val versionName: String?,
	val versionCode: Long?,
	val libVersion: Double? = null,
	val language: String?,
	val isInstalled: Boolean,
	val isNsfw: Boolean,
	val sourceCount: Int,
	val sourceNames: List<String>,
	val iconUrl: String? = null,
	val state: UnifiedSourcePackageState = if (isInstalled) {
		UnifiedSourcePackageState.INSTALLED
	} else {
		UnifiedSourcePackageState.AVAILABLE
	},
	val installedVersionName: String? = null,
	val installProgressPercent: Int? = null,
	val installLocation: UnifiedSourcePackageInstallLocation? = null,
	val installPayload: RepoAvailableExtension? = null,
	val lnReaderPayload: LNReaderPluginInfo? = null,
	val activeSourceCount: Int = 0,
	val shadowedSourceCount: Int = 0,
)

data class UnifiedSourceItem(
	val id: String,
	val kind: UnifiedSourceKind,
	val source: ContentSource,
	val title: String,
	val language: String?,
	val contentType: ContentType,
	val repositoryId: String?,
	val repositoryName: String?,
	val packageId: String?,
	val packageName: String?,
	val isEnabled: Boolean,
	val isPinned: Boolean,
	val isAvailable: Boolean,
	val isInstalled: Boolean,
	val isNsfw: Boolean,
	val isBroken: Boolean,
)

data class UnifiedSourceCatalogState(
	val repositories: List<UnifiedSourceRepositoryItem>,
	val packages: List<UnifiedSourcePackageItem>,
	val sources: List<UnifiedSourceItem>,
)
