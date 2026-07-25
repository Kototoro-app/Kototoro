package org.skepsun.kototoro.settings.userdata.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.local.data.CacheDir
import org.skepsun.kototoro.settings.compose.DataCleanupSettingsScreen

@Composable
fun DataCleanupSettingsRoute(
    settings: AppSettings,
    viewModel: DataCleanupSettingsViewModel,
    onClearLocalManga: () -> Unit,
    onClearLocalNovels: () -> Unit,
    onClearLocalVideos: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onClearCookies: () -> Unit,
    onDeleteReadChapters: () -> Unit,
    onOpenEntityOrganize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = context.resources

    val searchHistoryCount by viewModel.searchHistoryCount.collectAsState(initial = -1)
    val searchHistorySummary = if (searchHistoryCount < 0) {
        context.getString(R.string.loading_)
    } else {
        resources.getQuantityStringSafe(R.plurals.items, searchHistoryCount, searchHistoryCount)
    }

    val feedItemsCount by viewModel.feedItemsCount.collectAsState(initial = -1)
    val updatesFeedSummary = if (feedItemsCount < 0) {
        context.getString(R.string.loading_)
    } else {
        resources.getQuantityStringSafe(R.plurals.items, feedItemsCount, feedItemsCount)
    }

    val thumbsCacheSize by viewModel.cacheSizes[CacheDir.THUMBS]!!.collectAsState(initial = -1L)
    val thumbsCacheSummary = if (thumbsCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, thumbsCacheSize)
    }

    val faviconsCacheSize by viewModel.cacheSizes[CacheDir.FAVICONS]!!.collectAsState(initial = -1L)
    val faviconsCacheSummary = if (faviconsCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, faviconsCacheSize)
    }

    val pagesCacheSize by viewModel.cacheSizes[CacheDir.PAGES]!!.collectAsState(initial = -1L)
    val pagesCacheSummary = if (pagesCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, pagesCacheSize)
    }

    val novelCacheSize by viewModel.cacheSizes[CacheDir.NOVELS]!!.collectAsState(initial = -1L)
    val novelCacheSummary = if (novelCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, novelCacheSize)
    }

    val videoCacheSize by viewModel.cacheSizes[CacheDir.VIDEO]!!.collectAsState(initial = -1L)
    val videoCacheSummary = if (videoCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, videoCacheSize)
    }

    val videoProxyCacheSize by viewModel.cacheSizes[CacheDir.VIDEO_PROXY]!!.collectAsState(initial = -1L)
    val videoProxyCacheSummary = if (videoProxyCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, videoProxyCacheSize)
    }

    val danmakuCacheSize by viewModel.cacheSizes[CacheDir.DANMAKU]!!.collectAsState(initial = -1L)
    val danmakuCacheSummary = if (danmakuCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, danmakuCacheSize)
    }

    val ttsCacheSize by viewModel.cacheSizes[CacheDir.TtsAudio]!!.collectAsState(initial = -1L)
    val ttsCacheSummary = if (ttsCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, ttsCacheSize)
    }

    val superResolutionCacheSize by viewModel.cacheSizes[CacheDir.SUPER_RESOLUTION]!!.collectAsState(initial = -1L)
    val superResolutionCacheSummary = if (superResolutionCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, superResolutionCacheSize)
    }

    val httpCacheSize by viewModel.httpCacheSize.collectAsState(initial = -1L)
    val networkCacheSummary = if (httpCacheSize < 0) {
        context.getString(R.string.computing_)
    } else {
        FileSize.BYTES.format(context, httpCacheSize)
    }

    val loadingKeys by viewModel.loadingKeys.collectAsState(initial = emptySet())

    DataCleanupSettingsScreen(
        settings = settings,
        searchHistorySummary = searchHistorySummary,
        updatesFeedSummary = updatesFeedSummary,
        thumbsCacheSummary = thumbsCacheSummary,
        faviconsCacheSummary = faviconsCacheSummary,
        pagesCacheSummary = pagesCacheSummary,
        novelCacheSummary = novelCacheSummary,
        videoCacheSummary = videoCacheSummary,
        videoProxyCacheSummary = videoProxyCacheSummary,
        danmakuCacheSummary = danmakuCacheSummary,
        ttsCacheSummary = ttsCacheSummary,
        superResolutionCacheSummary = superResolutionCacheSummary,
        networkCacheSummary = networkCacheSummary,
        isBrowserVisible = viewModel.isBrowserDataCleanupEnabled,
        isLocalMangaEnabled = AppSettings.KEY_LOCAL_MANGA_CLEAR !in loadingKeys,
        isLocalNovelsEnabled = AppSettings.KEY_LOCAL_NOVELS_CLEAR !in loadingKeys,
        isLocalVideosEnabled = AppSettings.KEY_LOCAL_VIDEOS_CLEAR !in loadingKeys,
        isSearchHistoryEnabled = AppSettings.KEY_SEARCH_HISTORY_CLEAR !in loadingKeys,
        isUpdatesFeedEnabled = AppSettings.KEY_UPDATES_FEED_CLEAR !in loadingKeys,
        isThumbsCacheEnabled = AppSettings.KEY_THUMBS_CACHE_CLEAR !in loadingKeys,
        isFaviconsCacheEnabled = AppSettings.KEY_FAVICONS_CACHE_CLEAR !in loadingKeys,
        isPagesCacheEnabled = AppSettings.KEY_PAGES_CACHE_CLEAR !in loadingKeys,
        isNovelCacheEnabled = AppSettings.KEY_NOVEL_CACHE_CLEAR !in loadingKeys,
        isVideoCacheEnabled = AppSettings.KEY_VIDEO_CACHE_CLEAR !in loadingKeys,
        isVideoProxyCacheEnabled = AppSettings.KEY_VIDEO_PROXY_CACHE_CLEAR !in loadingKeys,
        isDanmakuCacheEnabled = AppSettings.KEY_VIDEO_DANMAKU_CACHE_CLEAR !in loadingKeys,
        isTtsCacheEnabled = AppSettings.KEY_TTS_CACHE_CLEAR !in loadingKeys,
        isSuperResolutionCacheEnabled = AppSettings.KEY_SR_CACHE_CLEAR !in loadingKeys,
        isNetworkCacheEnabled = AppSettings.KEY_HTTP_CACHE_CLEAR !in loadingKeys,
        isChaptersClearEnabled = AppSettings.KEY_CHAPTERS_CLEAR !in loadingKeys,
        isWebviewClearEnabled = AppSettings.KEY_WEBVIEW_CLEAR !in loadingKeys,
        isMangaDataEnabled = AppSettings.KEY_CLEAR_MANGA_DATA !in loadingKeys,
        onClearLocalManga = onClearLocalManga,
        onClearLocalNovels = onClearLocalNovels,
        onClearLocalVideos = onClearLocalVideos,
        onClearSearchHistory = onClearSearchHistory,
        onClearUpdatesFeed = viewModel::clearUpdatesFeed,
        onClearThumbsCache = {
            viewModel.clearCache(
                AppSettings.KEY_THUMBS_CACHE_CLEAR,
                CacheDir.THUMBS,
                CacheDir.FAVICONS,
            )
        },
        onClearFaviconsCache = {
            viewModel.clearCache(AppSettings.KEY_FAVICONS_CACHE_CLEAR, CacheDir.FAVICONS)
        },
        onClearPagesCache = {
            viewModel.clearCache(AppSettings.KEY_PAGES_CACHE_CLEAR, CacheDir.PAGES)
        },
        onClearNovelCache = {
            viewModel.clearCache(AppSettings.KEY_NOVEL_CACHE_CLEAR, CacheDir.NOVELS)
        },
        onClearVideoCache = {
            viewModel.clearCache(AppSettings.KEY_VIDEO_CACHE_CLEAR, CacheDir.VIDEO)
        },
        onClearVideoProxyCache = {
            viewModel.clearCache(AppSettings.KEY_VIDEO_PROXY_CACHE_CLEAR, CacheDir.VIDEO_PROXY)
        },
        onClearDanmakuCache = {
            viewModel.clearCache(AppSettings.KEY_VIDEO_DANMAKU_CACHE_CLEAR, CacheDir.DANMAKU)
        },
        onClearTtsCache = {
            viewModel.clearCache(AppSettings.KEY_TTS_CACHE_CLEAR, CacheDir.TtsAudio)
        },
        onClearSuperResolutionCache = {
            viewModel.clearCache(AppSettings.KEY_SR_CACHE_CLEAR, CacheDir.SUPER_RESOLUTION)
        },
        onClearNetworkCache = viewModel::clearHttpCache,
        onClearDatabase = viewModel::clearContentData,
        onClearCookies = onClearCookies,
        onClearBrowserData = viewModel::clearBrowserData,
        onDeleteReadChapters = onDeleteReadChapters,
        onOpenEntityOrganize = onOpenEntityOrganize,
        modifier = modifier,
    )
}
