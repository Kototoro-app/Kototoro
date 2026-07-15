package org.skepsun.kototoro.settings

import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

sealed interface SettingsDestination {

	data object Root : SettingsDestination
	data object AppearanceSettings : SettingsDestination
	data object UsersSettings : SettingsDestination
	data object SpacesSettings : SettingsDestination
	data object AISettings : SettingsDestination
	data object OcrModelsSettings : SettingsDestination
	data object AiImageEnhancementSettings : SettingsDestination
	data object AiVideoEnhancementSettings : SettingsDestination
	data object TtsSettings : SettingsDestination
	data object PlaybackSettings : SettingsDestination
	data object ReaderSettings : SettingsDestination
	data object SourcesSettings : SettingsDestination
	data object SuggestionsSettings : SettingsDestination
	data object SyncSettings : SettingsDestination
	data object BackupsSettings : SettingsDestination
	data object EntityOrganizeSettings : SettingsDestination
	data object TranslationSettings : SettingsDestination
	data object TranslationApiSettings : SettingsDestination
	data object TranslationE2EApiSettings : SettingsDestination
	data object StorageAndNetworkSettings : SettingsDestination
	data object CacheLimitsSettings : SettingsDestination
	data object DataCleanupSettings : SettingsDestination
	data object DownloadsSettings : SettingsDestination
	data object TrackerSettings : SettingsDestination
	data object NotificationSettings : SettingsDestination
	data object ServicesSettings : SettingsDestination
	data object DiscordSettings : SettingsDestination
	data object ProxySettings : SettingsDestination
	data object NavConfigSettings : SettingsDestination
	data object ChangelogSettings : SettingsDestination
	data object AboutSettings : SettingsDestination

	data class SourceSettings(
		val sourceName: String,
	) : SettingsDestination

	data class UnifiedSources(
		val initialRepositoryKind: UnifiedSourceKind? = null,
		val initialRepositoryUrl: String? = null,
	) : SettingsDestination
}
