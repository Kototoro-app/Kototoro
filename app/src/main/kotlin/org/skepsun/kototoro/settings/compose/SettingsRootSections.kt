package org.skepsun.kototoro.settings.compose

import android.content.Context
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.settings.SettingsDestination

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

	val coreSection = SettingsRootSection(
		title = context.getString(R.string.settings),
		items = listOf(
			settingsRootItem(
				key = "appearance",
				iconRes = R.drawable.ic_appearance,
				title = context.getString(R.string.appearance),
				summary = context.summaryOf(R.string.theme, R.string.list_mode, R.string.language),
				onClick = { onOpenDestination(SettingsDestination.AppearanceSettings) },
			),
			settingsRootItem(
				key = "remote_sources",
				iconRes = R.drawable.ic_manga_source,
				title = context.getString(R.string.remote_sources),
				summary = contentSummary,
				onClick = { onOpenDestination(SettingsDestination.SourcesSettings) },
			),
			settingsRootItem(
				key = "extension_management",
				iconRes = R.drawable.ic_extension,
				title = context.getString(R.string.extension_management),
				summary = context.getString(R.string.extension_management_summary),
				onClick = { onOpenDestination(SettingsDestination.UnifiedSources()) },
			),
			settingsRootItem(
				key = "reader",
				iconRes = R.drawable.ic_book_page,
				title = context.getString(R.string.reader_settings),
				summary = context.summaryOf(R.string.read_mode, R.string.scale_mode, R.string.switch_pages),
				onClick = { onOpenDestination(SettingsDestination.ReaderSettings) },
			),
			settingsRootItem(
				key = "ai",
				iconRes = R.drawable.ic_auto_fix,
				title = context.getString(R.string.ai_settings),
				summary = context.getString(R.string.ai_settings_entry_summary),
				onClick = { onOpenDestination(SettingsDestination.AISettings) },
			),
			settingsRootItem(
				key = "playback",
				iconRes = R.drawable.ic_play,
				title = context.getString(R.string.playback_settings),
				summary = context.summaryOf(R.string.video_decoder_mode, R.string.video_cache_size),
				onClick = { onOpenDestination(SettingsDestination.PlaybackSettings) },
			),
		),
	)

	val usersSection = SettingsRootSection(
		title = context.getString(R.string.users),
		items = listOf(
			settingsRootItem(
				key = "spaces",
				iconRes = R.drawable.ic_list_group,
				title = context.getString(R.string.spaces),
				summary = context.getString(R.string.spaces_settings_summary),
				onClick = { onOpenDestination(SettingsDestination.SpacesSettings) },
			),
			settingsRootItem(
				key = "sync",
				iconRes = R.drawable.ic_sync,
				title = context.getString(R.string.sync_settings),
				summary = context.getString(R.string.sync_settings_summary),
				onClick = { onOpenDestination(SettingsDestination.SyncSettings) },
			),
			settingsRootItem(
				key = "tracking_accounts",
				iconRes = R.drawable.ic_user,
				title = context.getString(R.string.tracking_accounts),
				summary = context.summaryOf(R.string.tracking, R.string.preferred_tracking_site),
				onClick = { onOpenDestination(SettingsDestination.UsersSettings) },
			),
			settingsRootItem(
				key = "backups_settings",
				iconRes = R.drawable.ic_backup_restore,
				title = context.getString(R.string.backup_restore),
				summary = context.summaryOf(R.string.create_backup, R.string.restore_backup, R.string.webdav_integration),
				onClick = { onOpenDestination(SettingsDestination.BackupsSettings) },
			),
			settingsRootItem(
				key = "entity_organize_settings",
				iconRes = R.drawable.ic_select_group,
				title = context.getString(R.string.entity_organize_title),
				summary = context.getString(R.string.entity_organize_settings_summary),
				onClick = { onOpenDestination(SettingsDestination.EntityOrganizeSettings) },
			),
		),
	)

	val servicesSection = SettingsRootSection(
		title = context.getString(R.string.services),
		items = buildList {
			add(
				settingsRootItem(
					key = "network",
					iconRes = R.drawable.ic_usage,
					title = context.getString(R.string.storage_and_network),
					summary = context.summaryOf(R.string.storage_usage, R.string.proxy, R.string.prefetch_content),
					onClick = { onOpenDestination(SettingsDestination.StorageAndNetworkSettings) },
				),
			)
			add(
				settingsRootItem(
					key = "downloads",
					iconRes = R.drawable.ic_download,
					title = context.getString(R.string.downloads),
					summary = context.summaryOf(R.string.manga_save_location, R.string.downloads_wifi_only),
					onClick = { onOpenDestination(SettingsDestination.DownloadsSettings) },
				),
			)
			add(
				settingsRootItem(
					key = "tracker",
					iconRes = R.drawable.ic_feed,
					title = context.getString(R.string.check_for_new_chapters),
					summary = context.summaryOf(R.string.track_sources, R.string.notifications_settings),
					onClick = { onOpenDestination(SettingsDestination.TrackerSettings) },
				),
			)
			add(
				settingsRootItem(
					key = "services",
					iconRes = R.drawable.ic_services,
					title = context.getString(R.string.services),
					summary = context.summaryOf(R.string.suggestions, R.string.reading_stats),
					onClick = { onOpenDestination(SettingsDestination.ServicesSettings) },
				),
			)
			add(
				settingsRootItem(
					key = "about",
					iconRes = R.drawable.ic_info_outline,
					title = context.getString(R.string.about),
					summary = context.getString(R.string.app_version, BuildConfig.VERSION_NAME),
					onClick = { onOpenDestination(SettingsDestination.AboutSettings) },
				),
			)
		},
	)

	return listOf(usersSection, coreSection, servicesSection)
}

private fun settingsRootItem(
	key: String,
	iconRes: Int,
	title: String,
	summary: String,
	onClick: () -> Unit,
): SettingsRootItem {
	return SettingsRootItem(
		key = key,
		iconRes = iconRes,
		title = title,
		summary = summary,
		onClick = onClick,
	)
}

private fun Context.summaryOf(vararg items: Int): String {
	return items.joinToString { getString(it) }
}
