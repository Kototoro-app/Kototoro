package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings

@Composable
fun DataCleanupSettingsScreen(
    settings: AppSettings,
    searchHistorySummary: String,
    updatesFeedSummary: String,
    thumbsCacheSummary: String,
    pagesCacheSummary: String,
    videoCacheSummary: String,
    networkCacheSummary: String,
    isBrowserVisible: Boolean,
    isSearchHistoryEnabled: Boolean,
    isUpdatesFeedEnabled: Boolean,
    isThumbsCacheEnabled: Boolean,
    isPagesCacheEnabled: Boolean,
    isVideoCacheEnabled: Boolean,
    isNetworkCacheEnabled: Boolean,
    isChaptersClearEnabled: Boolean,
    isWebviewClearEnabled: Boolean,
    isMangaDataEnabled: Boolean,
    onClearSearchHistory: () -> Unit,
    onClearUpdatesFeed: () -> Unit,
    onClearThumbsCache: () -> Unit,
    onClearPagesCache: () -> Unit,
    onClearVideoCache: () -> Unit,
    onClearNetworkCache: () -> Unit,
    onClearDatabase: () -> Unit,
    onClearCookies: () -> Unit,
    onClearBrowserData: () -> Unit,
    onDeleteReadChapters: () -> Unit,
    onOpenEntityOrganize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
    ) {
        SettingsPreferenceSection(title = stringResource(R.string.data_removal)) {
            SettingsActionPreference(
                title = stringResource(R.string.clear_search_history),
                summary = searchHistorySummary,
                enabled = isSearchHistoryEnabled,
                onClick = onClearSearchHistory,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_updates_feed),
                summary = updatesFeedSummary,
                enabled = isUpdatesFeedEnabled,
                onClick = onClearUpdatesFeed,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_thumbs_cache),
                summary = thumbsCacheSummary,
                enabled = isThumbsCacheEnabled,
                onClick = onClearThumbsCache,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_pages_cache),
                summary = pagesCacheSummary,
                enabled = isPagesCacheEnabled,
                onClick = onClearPagesCache,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_video_cache),
                summary = videoCacheSummary,
                enabled = isVideoCacheEnabled,
                onClick = onClearVideoCache,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_network_cache),
                summary = networkCacheSummary,
                enabled = isNetworkCacheEnabled,
                onClick = onClearNetworkCache,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_database),
                summary = stringResource(R.string.clear_database_summary),
                enabled = isMangaDataEnabled,
                onClick = onClearDatabase,
            )
            SettingsActionPreference(
                title = stringResource(R.string.clear_cookies),
                summary = stringResource(R.string.clear_cookies_summary),
                onClick = onClearCookies,
            )
            if (isBrowserVisible) {
                SettingsActionPreference(
                    title = stringResource(R.string.clear_browser_data),
                    summary = stringResource(R.string.clear_browser_data_summary),
                    enabled = isWebviewClearEnabled,
                    onClick = onClearBrowserData,
                )
            }
        }
        SettingsPreferenceSection(title = stringResource(R.string.chapters)) {
            SettingsActionPreference(
                title = stringResource(R.string.delete_read_chapters),
                summary = stringResource(R.string.delete_read_chapters_summary),
                enabled = isChaptersClearEnabled,
                onClick = onDeleteReadChapters,
            )
            SettingsSwitchPreference(
                title = stringResource(R.string.delete_read_chapters_auto),
                summary = stringResource(R.string.runs_on_app_start),
                checked = settings.prefs.getBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, false),
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, checked).apply()
                },
            )
        }
        SettingsPreferenceSection(title = stringResource(R.string.entity_reset_title)) {
            SettingsActionPreference(
                title = stringResource(R.string.entity_reset),
                summary = stringResource(R.string.entity_reset_description),
                onClick = onOpenEntityOrganize,
            )
        }
    }
}
