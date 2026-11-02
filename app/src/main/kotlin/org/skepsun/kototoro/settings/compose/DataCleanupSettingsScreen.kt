package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.settings.DataCleanupContent
import org.skepsun.kototoro.core.ui.settings.DataCleanupRow
import org.skepsun.kototoro.core.ui.settings.DataCleanupSection

/**
 * Android's data removal page. The sections, the rows and the read-chapter switch are the shared [DataCleanupContent]
 * Windows draws too; this screen only knows which of Android's operations are available right now (an empty cache, a
 * browser that is not installed) and which drawable each row leads with.
 */
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
    modifier: Modifier = Modifier,
) {
    val localStorage = DataCleanupSection(
        key = "local_storage",
        title = stringResource(R.string.local_storage),
        rows = listOf(
            DataCleanupRow("clear_local_manga", stringResource(R.string.clear_local_manga_storage),
                enabled = isLocalMangaEnabled, onClick = onClearLocalManga,
                leadingIcon = painterResource(R.drawable.ic_storage)),
            DataCleanupRow("clear_local_novels", stringResource(R.string.clear_local_novel_storage),
                enabled = isLocalNovelsEnabled, onClick = onClearLocalNovels,
                leadingIcon = painterResource(R.drawable.ic_storage)),
            DataCleanupRow("clear_local_videos", stringResource(R.string.clear_local_video_storage),
                enabled = isLocalVideosEnabled, onClick = onClearLocalVideos,
                leadingIcon = painterResource(R.drawable.ic_storage)),
        ),
    )
    val cache = DataCleanupSection(
        key = "cache",
        title = stringResource(R.string.cache),
        rows = listOf(
            DataCleanupRow("clear_thumbs_cache", stringResource(R.string.clear_thumbs_cache),
                summary = thumbsCacheSummary, enabled = isThumbsCacheEnabled, onClick = onClearThumbsCache,
                leadingIcon = painterResource(R.drawable.ic_images)),
            DataCleanupRow("clear_favicons_cache", stringResource(R.string.clear_favicons_cache),
                summary = faviconsCacheSummary, enabled = isFaviconsCacheEnabled, onClick = onClearFaviconsCache,
                leadingIcon = painterResource(R.drawable.ic_web)),
            DataCleanupRow("clear_pages_cache", stringResource(R.string.clear_pages_cache),
                summary = pagesCacheSummary, enabled = isPagesCacheEnabled, onClick = onClearPagesCache,
                leadingIcon = painterResource(R.drawable.ic_book_page)),
            DataCleanupRow("clear_novel_cache", stringResource(R.string.clear_novel_cache),
                summary = novelCacheSummary, enabled = isNovelCacheEnabled, onClick = onClearNovelCache,
                leadingIcon = painterResource(R.drawable.ic_read)),
            DataCleanupRow("clear_video_cache", stringResource(R.string.clear_video_cache),
                summary = videoCacheSummary, enabled = isVideoCacheEnabled, onClick = onClearVideoCache,
                leadingIcon = painterResource(R.drawable.ic_content_video)),
            DataCleanupRow("clear_video_proxy_cache", stringResource(R.string.clear_video_proxy_cache),
                summary = videoProxyCacheSummary, enabled = isVideoProxyCacheEnabled,
                onClick = onClearVideoProxyCache, leadingIcon = painterResource(R.drawable.ic_dns)),
            DataCleanupRow("clear_torrent_cache", stringResource(R.string.clear_torrent_cache),
                summary = torrentCacheSummary, enabled = isTorrentCacheEnabled, onClick = onClearTorrentCache,
                leadingIcon = painterResource(R.drawable.ic_network_cellular)),
            DataCleanupRow("clear_danmaku_cache", stringResource(R.string.clear_danmaku_cache),
                summary = danmakuCacheSummary, enabled = isDanmakuCacheEnabled, onClick = onClearDanmakuCache,
                leadingIcon = painterResource(R.drawable.ic_danmaku)),
            DataCleanupRow("clear_tts_cache", stringResource(R.string.clear_tts_audio_cache),
                summary = ttsCacheSummary, enabled = isTtsCacheEnabled, onClick = onClearTtsCache,
                leadingIcon = painterResource(R.drawable.ic_voice_input)),
            DataCleanupRow("clear_super_resolution_cache",
                stringResource(R.string.reader_super_resolution_clear_cache),
                summary = superResolutionCacheSummary, enabled = isSuperResolutionCacheEnabled,
                onClick = onClearSuperResolutionCache, leadingIcon = painterResource(R.drawable.ic_zoom_in)),
            DataCleanupRow("clear_network_cache", stringResource(R.string.clear_network_cache),
                summary = networkCacheSummary, enabled = isNetworkCacheEnabled, onClick = onClearNetworkCache,
                leadingIcon = painterResource(R.drawable.ic_web)),
        ),
    )
    val privacy = DataCleanupSection(
        key = "privacy",
        title = stringResource(R.string.privacy),
        rows = buildList {
            add(DataCleanupRow("clear_search_history", stringResource(R.string.clear_search_history),
                summary = searchHistorySummary, enabled = isSearchHistoryEnabled, onClick = onClearSearchHistory,
                leadingIcon = painterResource(R.drawable.ic_search)))
            add(DataCleanupRow("clear_updates_feed", stringResource(R.string.clear_updates_feed),
                summary = updatesFeedSummary, enabled = isUpdatesFeedEnabled, onClick = onClearUpdatesFeed,
                leadingIcon = painterResource(R.drawable.ic_feed)))
            add(DataCleanupRow("clear_database", stringResource(R.string.clear_database),
                summary = stringResource(R.string.clear_database_summary), enabled = isMangaDataEnabled,
                onClick = onClearDatabase, leadingIcon = painterResource(R.drawable.ic_database)))
            add(DataCleanupRow("clear_cookies", stringResource(R.string.clear_cookies),
                summary = stringResource(R.string.clear_cookies_summary), onClick = onClearCookies,
                leadingIcon = painterResource(R.drawable.ic_cookie)))
            // Android only offers the WebView row where a browser can exist at all.
            if (isBrowserVisible) {
                add(DataCleanupRow("clear_browser_data", stringResource(R.string.clear_browser_data),
                    summary = stringResource(R.string.clear_browser_data_summary), enabled = isWebviewClearEnabled,
                    onClick = onClearBrowserData, leadingIcon = painterResource(R.drawable.ic_web)))
            }
        },
    )
    val chaptersClearAuto = settings.prefs.getBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, false)
    val chapters = DataCleanupSection(
        key = "chapters",
        title = stringResource(R.string.chapters),
        rows = listOf(
            DataCleanupRow("delete_read_chapters", stringResource(R.string.delete_read_chapters),
                summary = stringResource(R.string.delete_read_chapters_summary), enabled = isChaptersClearEnabled,
                onClick = onDeleteReadChapters, leadingIcon = painterResource(R.drawable.ic_delete)),
            DataCleanupRow("delete_read_chapters_auto", stringResource(R.string.delete_read_chapters_auto),
                summary = stringResource(R.string.runs_on_app_start), checked = chaptersClearAuto,
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, checked).apply()
                },
                leadingIcon = painterResource(R.drawable.ic_timer)),
        ),
    )
    DataCleanupContent(
        sections = listOf(localStorage, cache, privacy, chapters),
        modifier = modifier.fillMaxSize(),
        // The page scrolls under the settings top bar, so the inset has to travel inside the scroll container.
        contentPadding = PaddingValues(
            start = SettingsContentHorizontalPadding,
            end = SettingsContentHorizontalPadding,
            top = settingsContentTopInset(),
            bottom = 8.dp,
        ),
        tagPrefix = "data-cleanup",
    )
}
