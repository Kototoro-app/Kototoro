package org.skepsun.kototoro.settings.compose

import android.content.Context
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.settings.SettingsDestination
import org.skepsun.kototoro.settings.sources.unified.RecoveryBadgeProvider

fun buildSettingsRootSections(
    context: Context,
    enabledSourcesCount: Int,
    totalSourcesCount: Int,
    onOpenDestination: (SettingsDestination) -> Unit,
): List<SettingsRootSection> {
    val contentSummary = if (enabledSourcesCount >= 0) {
        context.getString(R.string.enabled_d_of_d, enabledSourcesCount, totalSourcesCount)
    } else {
        context.resources.getQuantityStringSafe(R.plurals.items, totalSourcesCount, totalSourcesCount)
    }
    // T5.3: recovery badge on the source-management entry. The count is a process-wide cache
    // refreshed by `UnifiedSourcesViewModel` (see RecoveryBadgeProvider); recomputed whenever
    // the settings root re-composes (e.g. after returning from the unified sources screen).
    val recoveryMissingCount = RecoveryBadgeProvider.count()
    val extensionManagementSummary = if (recoveryMissingCount > 0) {
        context.getString(R.string.recovery_root_badge_summary, recoveryMissingCount)
    } else {
        context.getString(R.string.extension_management_summary)
    }

    val readingInterfaceSection = SettingsRootSection(
        title = context.getString(R.string.settings_section_reading_interface),
        items = listOf(
            settingsRootItem(
                key = "appearance",
                iconRes = R.drawable.ic_appearance,
                iosIconColor = SettingsRootIconColor.BLUE,
                title = context.getString(R.string.appearance),
                summary = context.summaryOf(R.string.theme, R.string.list_mode, R.string.language),
                onClick = { onOpenDestination(SettingsDestination.AppearanceSettings) },
            ),
            settingsRootItem(
                key = "reader",
                iconRes = R.drawable.ic_book_page,
                iosIconColor = SettingsRootIconColor.ORANGE,
                title = context.getString(R.string.reader_settings),
                summary = context.summaryOf(R.string.read_mode, R.string.scale_mode, R.string.switch_pages),
                onClick = { onOpenDestination(SettingsDestination.ReaderSettings) },
            ),
            settingsRootItem(
                key = "ai",
                iconRes = R.drawable.ic_auto_fix,
                iosIconColor = SettingsRootIconColor.PURPLE,
                title = context.getString(R.string.ai_settings),
                summary = context.getString(R.string.ai_settings_entry_summary),
                onClick = { onOpenDestination(SettingsDestination.AISettings) },
            ),
            settingsRootItem(
                key = "playback",
                iconRes = R.drawable.ic_play,
                iosIconColor = SettingsRootIconColor.RED,
                title = context.getString(R.string.playback_settings),
                summary = context.summaryOf(R.string.video_decoder_mode, R.string.video_cache_size),
                onClick = { onOpenDestination(SettingsDestination.PlaybackSettings) },
            ),
        ),
    )

    val contentSourcesSection = SettingsRootSection(
        title = context.getString(R.string.settings_section_content_sources),
        items = listOf(
            settingsRootItem(
                key = "remote_sources",
                iconRes = R.drawable.ic_manga_source,
                iosIconColor = SettingsRootIconColor.GREEN,
                title = context.getString(R.string.remote_sources),
                summary = contentSummary,
                onClick = { onOpenDestination(SettingsDestination.SourcesSettings) },
            ),
            settingsRootItem(
                key = "extension_management",
                iconRes = R.drawable.ic_extension,
                iosIconColor = SettingsRootIconColor.INDIGO,
                title = context.getString(R.string.extension_management),
                summary = extensionManagementSummary,
                onClick = { onOpenDestination(SettingsDestination.UnifiedSources()) },
            ),
            settingsRootItem(
                key = "downloads",
                iconRes = R.drawable.ic_download,
                iosIconColor = SettingsRootIconColor.BLUE,
                title = context.getString(R.string.downloads),
                summary = context.summaryOf(R.string.manga_save_location, R.string.downloads_wifi_only),
                onClick = { onOpenDestination(SettingsDestination.DownloadsSettings) },
            ),
        ),
    )

    val accountsDataSection = SettingsRootSection(
        title = context.getString(R.string.settings_section_accounts_data),
        items = listOf(
            settingsRootItem(
                key = "spaces",
                iconRes = R.drawable.ic_list_group,
                iosIconColor = SettingsRootIconColor.ORANGE,
                title = context.getString(R.string.spaces),
                summary = context.getString(R.string.spaces_settings_summary),
                onClick = { onOpenDestination(SettingsDestination.SpacesSettings) },
            ),
            settingsRootItem(
                key = "tracking_accounts",
                iconRes = R.drawable.ic_user,
                iosIconColor = SettingsRootIconColor.CYAN,
                title = context.getString(R.string.tracking_accounts),
                summary = context.summaryOf(R.string.tracking, R.string.preferred_tracking_site),
                onClick = { onOpenDestination(SettingsDestination.UsersSettings) },
            ),
            settingsRootItem(
                key = "sync",
                iconRes = R.drawable.ic_sync,
                iosIconColor = SettingsRootIconColor.GREEN,
                title = context.getString(R.string.sync_settings),
                summary = context.getString(R.string.sync_settings_summary),
                onClick = { onOpenDestination(SettingsDestination.SyncSettings) },
            ),
            settingsRootItem(
                key = "backups_settings",
                iconRes = R.drawable.ic_backup_restore,
                iosIconColor = SettingsRootIconColor.TEAL,
                title = context.getString(R.string.backup_restore),
                summary = context.summaryOf(R.string.create_backup, R.string.restore_backup, R.string.webdav_integration),
                onClick = { onOpenDestination(SettingsDestination.BackupsSettings) },
            ),
        ),
    )

    val systemServicesSection = SettingsRootSection(
        title = context.getString(R.string.settings_section_system_services),
        items = listOf(
            settingsRootItem(
                key = "network",
                iconRes = R.drawable.ic_usage,
                iosIconColor = SettingsRootIconColor.GRAY,
                title = context.getString(R.string.storage_and_network),
                summary = context.summaryOf(R.string.storage_usage, R.string.proxy, R.string.prefetch_content),
                onClick = { onOpenDestination(SettingsDestination.StorageAndNetworkSettings) },
            ),
            settingsRootItem(
                key = "tracker",
                iconRes = R.drawable.ic_feed,
                iosIconColor = SettingsRootIconColor.RED,
                title = context.getString(R.string.check_for_new_chapters),
                summary = context.summaryOf(R.string.track_sources, R.string.notifications_settings),
                onClick = { onOpenDestination(SettingsDestination.TrackerSettings) },
            ),
            settingsRootItem(
                key = "services",
                iconRes = R.drawable.ic_services,
                iosIconColor = SettingsRootIconColor.PURPLE,
                title = context.getString(R.string.services),
                summary = context.summaryOf(R.string.suggestions, R.string.reading_stats),
                onClick = { onOpenDestination(SettingsDestination.ServicesSettings) },
            ),
            settingsRootItem(
                key = "entity_organize_settings",
                iconRes = R.drawable.ic_select_group,
                iosIconColor = SettingsRootIconColor.ORANGE,
                title = context.getString(R.string.entity_organize_title),
                summary = context.getString(R.string.entity_organize_settings_summary),
                onClick = { onOpenDestination(SettingsDestination.EntityOrganizeSettings) },
            ),
        ),
    )

    val helpFeedbackSection = SettingsRootSection(
        title = "",
        items = listOf(
            settingsRootItem(
                key = "help_feedback",
                iconRes = R.drawable.ic_info_outline,
                iosIconColor = SettingsRootIconColor.BLUE,
                title = context.getString(R.string.help_and_feedback),
                summary = context.getString(R.string.app_version, BuildConfig.VERSION_NAME),
                onClick = { onOpenDestination(SettingsDestination.AboutSettings) },
            ),
        ),
    )

    return listOf(readingInterfaceSection, contentSourcesSection, accountsDataSection, systemServicesSection, helpFeedbackSection)
}

private fun settingsRootItem(
    key: String,
    iconRes: Int,
    iosIconColor: SettingsRootIconColor,
    title: String,
    summary: String,
    onClick: () -> Unit,
): SettingsRootItem {
    return SettingsRootItem(
        key = key,
        iconRes = iconRes,
        iosIconColor = iosIconColor,
        title = title,
        summary = summary,
        onClick = onClick,
    )
}

private fun Context.summaryOf(vararg items: Int): String {
    return items.joinToString { getString(it) }
}
