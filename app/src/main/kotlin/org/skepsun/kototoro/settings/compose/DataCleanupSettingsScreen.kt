package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
    torrentCacheSummary: String,
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
    isTorrentCacheEnabled: Boolean,
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
    onClearTorrentCache: () -> Unit,
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
            .padding(top = settingsContentTopInset())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsPreferenceGroup(title = stringResource(R.string.local_storage)) {
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_local_manga_storage),
                iconRes = R.drawable.ic_storage,
                enabled = isLocalMangaEnabled,
                showChevron = false,
                onClick = onClearLocalManga,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_local_novel_storage),
                iconRes = R.drawable.ic_storage,
                enabled = isLocalNovelsEnabled,
                showChevron = false,
                onClick = onClearLocalNovels,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_local_video_storage),
                iconRes = R.drawable.ic_storage,
                enabled = isLocalVideosEnabled,
                showChevron = false,
                onClick = onClearLocalVideos,
            ) }
        }
        SettingsPreferenceGroup(title = stringResource(R.string.cache)) {
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_thumbs_cache),
                iconRes = R.drawable.ic_images,
                summary = thumbsCacheSummary,
                enabled = isThumbsCacheEnabled,
                showChevron = false,
                onClick = onClearThumbsCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_favicons_cache),
                iconRes = R.drawable.ic_web,
                summary = faviconsCacheSummary,
                enabled = isFaviconsCacheEnabled,
                showChevron = false,
                onClick = onClearFaviconsCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_pages_cache),
                iconRes = R.drawable.ic_book_page,
                summary = pagesCacheSummary,
                enabled = isPagesCacheEnabled,
                showChevron = false,
                onClick = onClearPagesCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_novel_cache),
                iconRes = R.drawable.ic_read,
                summary = novelCacheSummary,
                enabled = isNovelCacheEnabled,
                showChevron = false,
                onClick = onClearNovelCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_video_cache),
                iconRes = R.drawable.ic_content_video,
                summary = videoCacheSummary,
                enabled = isVideoCacheEnabled,
                showChevron = false,
                onClick = onClearVideoCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_video_proxy_cache),
                iconRes = R.drawable.ic_dns,
                summary = videoProxyCacheSummary,
                enabled = isVideoProxyCacheEnabled,
                showChevron = false,
                onClick = onClearVideoProxyCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_torrent_cache),
                iconRes = R.drawable.ic_network_cellular,
                summary = torrentCacheSummary,
                enabled = isTorrentCacheEnabled,
                showChevron = false,
                onClick = onClearTorrentCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_danmaku_cache),
                iconRes = R.drawable.ic_danmaku,
                summary = danmakuCacheSummary,
                enabled = isDanmakuCacheEnabled,
                showChevron = false,
                onClick = onClearDanmakuCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_tts_audio_cache),
                iconRes = R.drawable.ic_voice_input,
                summary = ttsCacheSummary,
                enabled = isTtsCacheEnabled,
                showChevron = false,
                onClick = onClearTtsCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.reader_super_resolution_clear_cache),
                iconRes = R.drawable.ic_zoom_in,
                summary = superResolutionCacheSummary,
                enabled = isSuperResolutionCacheEnabled,
                showChevron = false,
                onClick = onClearSuperResolutionCache,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_network_cache),
                iconRes = R.drawable.ic_web,
                summary = networkCacheSummary,
                enabled = isNetworkCacheEnabled,
                showChevron = false,
                onClick = onClearNetworkCache,
            ) }
        }
        SettingsPreferenceGroup(title = stringResource(R.string.privacy)) {
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_search_history),
                iconRes = R.drawable.ic_search,
                summary = searchHistorySummary,
                enabled = isSearchHistoryEnabled,
                showChevron = false,
                onClick = onClearSearchHistory,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_updates_feed),
                iconRes = R.drawable.ic_feed,
                summary = updatesFeedSummary,
                enabled = isUpdatesFeedEnabled,
                showChevron = false,
                onClick = onClearUpdatesFeed,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_database),
                iconRes = R.drawable.ic_database,
                summary = stringResource(R.string.clear_database_summary),
                enabled = isMangaDataEnabled,
                showChevron = false,
                onClick = onClearDatabase,
            ) }
            item { SettingsActionPreference(
                title = stringResource(R.string.clear_cookies),
                iconRes = R.drawable.ic_cookie,
                summary = stringResource(R.string.clear_cookies_summary),
                showChevron = false,
                onClick = onClearCookies,
            ) }
            if (isBrowserVisible) {
                item { SettingsActionPreference(
                    title = stringResource(R.string.clear_browser_data),
                    iconRes = R.drawable.ic_web,
                    summary = stringResource(R.string.clear_browser_data_summary),
                    enabled = isWebviewClearEnabled,
                    showChevron = false,
                    onClick = onClearBrowserData,
                ) }
            }
        }
        SettingsPreferenceGroup(title = stringResource(R.string.chapters)) {
            item { SettingsActionPreference(
                title = stringResource(R.string.delete_read_chapters),
                iconRes = R.drawable.ic_delete,
                summary = stringResource(R.string.delete_read_chapters_summary),
                enabled = isChaptersClearEnabled,
                showChevron = false,
                onClick = onDeleteReadChapters,
            ) }
            item { SettingsSwitchPreference(
                title = stringResource(R.string.delete_read_chapters_auto),
                iconRes = R.drawable.ic_timer,
                summary = stringResource(R.string.runs_on_app_start),
                checked = settings.prefs.getBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, false),
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, checked).apply()
                },
            ) }
        }
        SettingsPreferenceGroup(title = stringResource(R.string.entity_reset_title)) {
            item { SettingsActionPreference(
                title = stringResource(R.string.entity_reset),
                iconRes = R.drawable.ic_delete_all,
                summary = stringResource(R.string.entity_reset_description),
                onClick = onOpenEntityOrganize,
            ) }
        }
    }
}
