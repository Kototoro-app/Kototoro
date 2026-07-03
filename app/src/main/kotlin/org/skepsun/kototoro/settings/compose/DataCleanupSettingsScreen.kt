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
    faviconsCacheSummary: String,
    pagesCacheSummary: String,
    novelCacheSummary: String,
    videoCacheSummary: String,
    videoProxyCacheSummary: String,
    danmakuCacheSummary: String,
    ttsCacheSummary: String,
    superResolutionCacheSummary: String,
    networkCacheSummary: String,
    isBrowserVisible: Boolean,
    isLocalMangaEnabled: Boolean,
    isLocalNovelsEnabled: Boolean,
    isLocalVideosEnabled: Boolean,
    isSearchHistoryEnabled: Boolean,
    isUpdatesFeedEnabled: Boolean,
    isThumbsCacheEnabled: Boolean,
    isFaviconsCacheEnabled: Boolean,
    isPagesCacheEnabled: Boolean,
    isNovelCacheEnabled: Boolean,
    isVideoCacheEnabled: Boolean,
    isVideoProxyCacheEnabled: Boolean,
    isDanmakuCacheEnabled: Boolean,
    isTtsCacheEnabled: Boolean,
    isSuperResolutionCacheEnabled: Boolean,
    isNetworkCacheEnabled: Boolean,
    isChaptersClearEnabled: Boolean,
    isWebviewClearEnabled: Boolean,
    isMangaDataEnabled: Boolean,
    onClearLocalManga: () -> Unit,
    onClearLocalNovels: () -> Unit,
    onClearLocalVideos: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onClearUpdatesFeed: () -> Unit,
    onClearThumbsCache: () -> Unit,
    onClearFaviconsCache: () -> Unit,
    onClearPagesCache: () -> Unit,
    onClearNovelCache: () -> Unit,
    onClearVideoCache: () -> Unit,
    onClearVideoProxyCache: () -> Unit,
    onClearDanmakuCache: () -> Unit,
    onClearTtsCache: () -> Unit,
    onClearSuperResolutionCache: () -> Unit,
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
        SettingsPreferenceSection(title = stringResource(R.string.local_storage)) {
            SettingsActionPreference(
                title = stringResource(R.string.clear_local_manga_storage),
                enabled = isLocalMangaEnabled,
                showChevron = false,
                onClick = onClearLocalManga,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_local_novel_storage),
                enabled = isLocalNovelsEnabled,
                showChevron = false,
                onClick = onClearLocalNovels,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_local_video_storage),
                enabled = isLocalVideosEnabled,
                showChevron = false,
                onClick = onClearLocalVideos,
            )
        }
        SettingsPreferenceSection(title = stringResource(R.string.cache)) {
            SettingsActionPreference(
                title = stringResource(R.string.clear_thumbs_cache),
                summary = thumbsCacheSummary,
                enabled = isThumbsCacheEnabled,
                showChevron = false,
                onClick = onClearThumbsCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_favicons_cache),
                summary = faviconsCacheSummary,
                enabled = isFaviconsCacheEnabled,
                showChevron = false,
                onClick = onClearFaviconsCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_pages_cache),
                summary = pagesCacheSummary,
                enabled = isPagesCacheEnabled,
                showChevron = false,
                onClick = onClearPagesCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_novel_cache),
                summary = novelCacheSummary,
                enabled = isNovelCacheEnabled,
                showChevron = false,
                onClick = onClearNovelCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_video_cache),
                summary = videoCacheSummary,
                enabled = isVideoCacheEnabled,
                showChevron = false,
                onClick = onClearVideoCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_video_proxy_cache),
                summary = videoProxyCacheSummary,
                enabled = isVideoProxyCacheEnabled,
                showChevron = false,
                onClick = onClearVideoProxyCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_danmaku_cache),
                summary = danmakuCacheSummary,
                enabled = isDanmakuCacheEnabled,
                showChevron = false,
                onClick = onClearDanmakuCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_tts_audio_cache),
                summary = ttsCacheSummary,
                enabled = isTtsCacheEnabled,
                showChevron = false,
                onClick = onClearTtsCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.reader_super_resolution_clear_cache),
                summary = superResolutionCacheSummary,
                enabled = isSuperResolutionCacheEnabled,
                showChevron = false,
                onClick = onClearSuperResolutionCache,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_network_cache),
                summary = networkCacheSummary,
                enabled = isNetworkCacheEnabled,
                showChevron = false,
                onClick = onClearNetworkCache,
            )
        }
        SettingsPreferenceSection(title = stringResource(R.string.privacy)) {
            SettingsActionPreference(
                title = stringResource(R.string.clear_search_history),
                summary = searchHistorySummary,
                enabled = isSearchHistoryEnabled,
                showChevron = false,
                onClick = onClearSearchHistory,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_updates_feed),
                summary = updatesFeedSummary,
                enabled = isUpdatesFeedEnabled,
                showChevron = false,
                onClick = onClearUpdatesFeed,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_database),
                summary = stringResource(R.string.clear_database_summary),
                enabled = isMangaDataEnabled,
                showChevron = false,
                onClick = onClearDatabase,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = stringResource(R.string.clear_cookies),
                summary = stringResource(R.string.clear_cookies_summary),
                showChevron = false,
                onClick = onClearCookies,
            )
            if (isBrowserVisible) {
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = stringResource(R.string.clear_browser_data),
                    summary = stringResource(R.string.clear_browser_data_summary),
                    enabled = isWebviewClearEnabled,
                    showChevron = false,
                    onClick = onClearBrowserData,
                )
            }
        }
        SettingsPreferenceSection(title = stringResource(R.string.chapters)) {
            SettingsActionPreference(
                title = stringResource(R.string.delete_read_chapters),
                summary = stringResource(R.string.delete_read_chapters_summary),
                enabled = isChaptersClearEnabled,
                showChevron = false,
                onClick = onDeleteReadChapters,
            )
            SettingsSectionDivider()
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
