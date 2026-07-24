package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.edit
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.Proxy
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.DoHProvider
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.NetworkPolicy
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.local.data.CacheDir
import org.skepsun.kototoro.settings.compose.SettingsActionPreference
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsChoicePreference
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding
import org.skepsun.kototoro.settings.compose.SettingsGroupLabel
import org.skepsun.kototoro.settings.compose.SettingsPreferenceSection
import org.skepsun.kototoro.settings.compose.SettingsSectionDivider
import org.skepsun.kototoro.settings.compose.SettingsSliderPreference
import org.skepsun.kototoro.settings.compose.SettingsSwitchPreference
import org.skepsun.kototoro.settings.compose.SettingsTextInputPreference
import org.skepsun.kototoro.settings.compose.StorageAndNetworkSettingsScreen
import org.skepsun.kototoro.settings.userdata.storage.DataCleanupSettingsViewModel
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory

@Composable
fun StorageAndNetworkSettingsRoute(
    settings: AppSettings,
    viewModel: StorageAndNetworkSettingsViewModel,
    dataCleanupViewModel: DataCleanupSettingsViewModel,
    onOpenCacheLimits: () -> Unit,
    onOpenDataRemoval: () -> Unit,
    onOpenProxySettings: () -> Unit,
    onConfirmClearSearchHistory: () -> Unit,
    onConfirmClearCookies: () -> Unit,
    onConfirmCleanupChapters: () -> Unit,
    onConfirmClearLocalManga: () -> Unit,
    onConfirmClearLocalNovels: () -> Unit,
    onConfirmClearLocalVideos: () -> Unit,
) {
    val context = LocalContext.current
    val storageUsage = viewModel.storageUsage.collectAsStateWithLifecycle().value
    val loadingKeys = dataCleanupViewModel.loadingKeys.collectAsStateWithLifecycle().value
    val searchHistoryCount = dataCleanupViewModel.searchHistoryCount.collectAsStateWithLifecycle().value
    val feedItemsCount = dataCleanupViewModel.feedItemsCount.collectAsStateWithLifecycle().value

    val prefetchPolicy = settings.observeAsState(AppSettings.KEY_PREFETCH_CONTENT) { contentPrefetchPolicy }.value
    val pagesPreloadPolicy = settings.observeAsState(AppSettings.KEY_PAGES_PRELOAD) { pagesPreloadPolicy }.value
    val dnsOverHttps = settings.observeAsState(AppSettings.KEY_DOH) { dnsOverHttps }.value
    val dohCustomUrl = settings.observeAsState(AppSettings.KEY_DOH_CUSTOM_URL) { dohCustomUrl.orEmpty() }.value
    val dohCustomIps = settings.observeAsState(AppSettings.KEY_DOH_CUSTOM_IPS) { dohCustomIps.orEmpty() }.value
    val imagesProxy = settings.observeAsState(AppSettings.KEY_IMAGES_PROXY) { imagesProxy }.value
    val gitHubMirror = settings.observeAsState(AppSettings.KEY_GITHUB_MIRROR) { gitHubMirror }.value
    val huggingFaceMirror = settings.observeAsState(AppSettings.KEY_HUGGINGFACE_MIRROR) { huggingFaceMirror }.value
    val bangumiMirror = settings.observeAsState(AppSettings.KEY_BANGUMI_MIRROR) { bangumiMirror }.value
    val bangumiMirrorCustomBase = settings.observeAsState(AppSettings.KEY_BANGUMI_MIRROR_CUSTOM_BASE) {
        bangumiMirrorCustomBase.orEmpty()
    }.value
    val sslBypass = settings.observeAsState(AppSettings.KEY_SSL_BYPASS) { isSSLBypassEnabled }.value
    val offlineDisabled = settings.observeAsState(AppSettings.KEY_OFFLINE_DISABLED) { isOfflineCheckDisabled }.value
    val adBlock = settings.observeAsState(AppSettings.KEY_ADBLOCK) { isAdBlockEnabled }.value

    val videoCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_CACHE_MB) { videoCacheSizeMb }.value
    val videoProxyCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_PROXY_CACHE_MB) { videoProxyCacheSizeMb }.value
    val videoDanmakuCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_DANMAKU_CACHE_MB) { videoDanmakuCacheSizeMb }.value
    val thumbsCacheMb = settings.observeAsState(AppSettings.KEY_THUMBS_CACHE_MB) { thumbsCacheSizeMb }.value
    val faviconCacheMb = settings.observeAsState(AppSettings.KEY_FAVICON_CACHE_MB) { faviconCacheSizeMb }.value
    val pagesCacheMb = settings.observeAsState(AppSettings.KEY_PAGES_CACHE_MB) { pagesCacheSizeMb }.value
    val novelCacheMb = settings.observeAsState(AppSettings.KEY_NOVEL_CACHE_MB) { novelCacheSizeMb }.value
    val httpCacheMb = settings.observeAsState(AppSettings.KEY_HTTP_CACHE_MB_LIMIT) { httpCacheSizeMb }.value
    val ttsCacheMb = settings.observeAsState(AppSettings.KEY_TTS_CACHE_MB) { ttsCacheSizeMb }.value
    val srCacheLimit = settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT) {
        settings.prefs.getString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "512") ?: "512"
    }.value

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val dohLabels = context.resources.getStringArray(R.array.doh_providers)
    val imageProxyLabels = context.resources.getStringArray(R.array.image_proxies)
    val srCacheLabels = context.resources.getStringArray(R.array.reader_super_resolution_cache_limits)
    val srCacheValues = context.resources.getStringArray(R.array.values_reader_super_resolution_cache_limits)

    val showRestartRequired: () -> Unit = {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.settings_apply_restart_required),
                duration = SnackbarDuration.Long,
            )
        }
    }

    LaunchedEffect(viewModel.onError, context, snackbarHostState) {
        viewModel.onError.collect { event ->
            event?.consume { error ->
                snackbarHostState.showSnackbar(error.getDisplayMessage(context.resources))
            }
        }
    }

    val networkOptions = listOf(
        SettingsChoiceOption(NetworkPolicy.ALWAYS, context.getString(R.string.always)),
        SettingsChoiceOption(NetworkPolicy.NON_METERED, context.getString(R.string.only_using_wifi)),
        SettingsChoiceOption(NetworkPolicy.NEVER, context.getString(R.string.never)),
    )
    val dohOptions = listOf(
        SettingsChoiceOption(DoHProvider.NONE, dohLabels[0]),
        SettingsChoiceOption(DoHProvider.CUSTOM, dohLabels[1]),
        SettingsChoiceOption(DoHProvider.GOOGLE, dohLabels[2]),
        SettingsChoiceOption(DoHProvider.CLOUDFLARE, dohLabels[3]),
        SettingsChoiceOption(DoHProvider.ADGUARD, dohLabels[4]),
        SettingsChoiceOption(DoHProvider.ZERO_MS, dohLabels[5]),
    )
    val imageProxyOptions = listOf(
        SettingsChoiceOption(-1, imageProxyLabels[0]),
        SettingsChoiceOption(0, imageProxyLabels[1]),
        SettingsChoiceOption(1, imageProxyLabels[2]),
    )
    val gitHubMirrorOptions = listOf(
        SettingsChoiceOption(AppSettings.GitHubMirror.NATIVE, "Direct Native (Default)"),
        SettingsChoiceOption(AppSettings.GitHubMirror.KKGITHUB, "KKGithub Proxy"),
        SettingsChoiceOption(AppSettings.GitHubMirror.GHPROXY, "Ghproxy.com"),
        SettingsChoiceOption(AppSettings.GitHubMirror.GHPROXY_NET, "Ghproxy.net"),
    )
    val huggingFaceMirrorOptions = listOf(
        SettingsChoiceOption(AppSettings.HuggingFaceMirror.NATIVE, "Direct Native (Default)"),
        SettingsChoiceOption(AppSettings.HuggingFaceMirror.HF_MIRROR, "hf-mirror.com"),
    )
    val bangumiMirrorOptions = listOf(
        SettingsChoiceOption(AppSettings.BangumiMirror.BANGUMI_LOL, "bangumi.lol (Default)"),
        SettingsChoiceOption(AppSettings.BangumiMirror.NATIVE, "Official"),
        SettingsChoiceOption(AppSettings.BangumiMirror.CUSTOM, "Custom"),
    )

    StorageAndNetworkSettingsScreen(
        storageTitle = context.getString(R.string.storage_usage),
        cacheLimitsTitle = context.getString(R.string.cache_limits),
        dataRemovalTitle = context.getString(R.string.data_removal),
        networkTitle = context.getString(R.string.network),
        storageUsage = storageUsage,
        snackbarHostState = snackbarHostState,
        onCacheLimitsClick = onOpenCacheLimits,
        onDataRemovalClick = onOpenDataRemoval,
        cacheLimits = {
            SettingsGroupLabel(text = context.getString(R.string.image_caches))
            SettingsSliderPreference(
                title = context.getString(R.string.thumbnails_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = thumbsCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.thumbsCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.favicons_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = faviconCacheMb,
                valueRange = 4..128,
                step = 4,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.faviconCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.pages_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = pagesCacheMb,
                valueRange = 64..4096,
                step = 64,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.pagesCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.novel_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = novelCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.novelCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.tts_audio_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = ttsCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.ttsCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsChoicePreference(
                title = context.getString(R.string.reader_super_resolution_cache_limit),
                value = srCacheLimit,
                options = srCacheLabels.mapIndexed { index, label ->
                    SettingsChoiceOption(srCacheValues[index], label)
                },
                onValueChange = {
                    settings.prefs.edit().putString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, it).apply()
                },
            )
            SettingsSectionDivider()
            SettingsGroupLabel(text = context.getString(R.string.video_caches))
            SettingsSliderPreference(
                title = context.getString(R.string.video_playback_cache_limit),
                value = videoCacheMb,
                valueRange = 256..4096,
                step = 128,
                valueText = { "$it MB" },
                onValueChange = { settings.videoCacheSizeMb = it },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.video_proxy_cache_limit),
                value = videoProxyCacheMb,
                valueRange = 128..4096,
                step = 128,
                valueText = { "$it MB" },
                onValueChange = { settings.videoProxyCacheSizeMb = it },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.danmaku_cache_limit),
                value = videoDanmakuCacheMb,
                valueRange = 16..1024,
                step = 16,
                valueText = { "$it MB" },
                onValueChange = { settings.videoDanmakuCacheSizeMb = it },
            )
            SettingsSectionDivider()
            SettingsGroupLabel(text = context.getString(R.string.network))
            SettingsSliderPreference(
                title = context.getString(R.string.network_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = httpCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.httpCacheSizeMb = it
                    showRestartRequired()
                },
            )
        },
        prefetchContent = {
            SettingsChoicePreference(
                title = context.getString(R.string.prefetch_content),
                value = prefetchPolicy,
                options = networkOptions,
                onValueChange = { settings.contentPrefetchPolicy = it },
            )
        },
        preloadPages = {
            SettingsChoicePreference(
                title = context.getString(R.string.preload_pages),
                value = pagesPreloadPolicy,
                options = networkOptions,
                onValueChange = { settings.pagesPreloadPolicy = it },
            )
        },
        proxy = {
            SettingsActionPreference(
                title = context.getString(R.string.proxy),
                summary = buildProxySummary(settings, context),
                onClick = onOpenProxySettings,
            )
        },
        dns = {
            SettingsChoicePreference(
                title = context.getString(R.string.dns_over_https),
                value = dnsOverHttps,
                options = dohOptions,
                onValueChange = { settings.dnsOverHttps = it },
            )
        },
        customDohUrl = {
            if (dnsOverHttps == DoHProvider.CUSTOM) {
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = context.getString(R.string.pref_doh_custom_url),
                    value = dohCustomUrl,
                    onValueChange = { settings.dohCustomUrl = it },
                )
            }
        },
        customDohIps = {
            if (dnsOverHttps == DoHProvider.CUSTOM) {
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = context.getString(R.string.pref_doh_custom_ips),
                    value = dohCustomIps,
                    onValueChange = { settings.dohCustomIps = it },
                )
            }
        },
        imageProxy = {
            SettingsChoicePreference(
                title = context.getString(R.string.images_proxy_title),
                value = imagesProxy,
                options = imageProxyOptions,
                onValueChange = { settings.imagesProxy = it },
            )
        },
        githubMirror = {
            SettingsChoicePreference(
                title = context.getString(R.string.pref_github_mirror),
                value = gitHubMirror,
                options = gitHubMirrorOptions,
                summary = context.getString(R.string.pref_github_mirror_summary),
                onValueChange = { settings.gitHubMirror = it },
            )
        },
        huggingFaceMirror = {
            SettingsChoicePreference(
                title = context.getString(R.string.pref_huggingface_mirror),
                value = huggingFaceMirror,
                options = huggingFaceMirrorOptions,
                summary = context.getString(R.string.pref_huggingface_mirror_summary),
                onValueChange = { settings.huggingFaceMirror = it },
            )
        },
        bangumiMirror = {
            SettingsChoicePreference(
                title = context.getString(R.string.pref_bangumi_mirror),
                value = bangumiMirror,
                options = bangumiMirrorOptions,
                summary = context.getString(R.string.pref_bangumi_mirror_summary),
                onValueChange = { settings.bangumiMirror = it },
            )
        },
        bangumiMirrorCustomBase = {
            if (bangumiMirror == AppSettings.BangumiMirror.CUSTOM) {
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = context.getString(R.string.pref_bangumi_mirror_custom_base),
                    value = bangumiMirrorCustomBase,
                    summary = context.getString(R.string.pref_bangumi_mirror_custom_base_summary),
                    placeholder = "https://bangumi.lol",
                    onValueChange = { settings.bangumiMirrorCustomBase = it },
                )
            }
        },
        sslBypass = {
            SettingsSwitchPreference(
                title = context.getString(R.string.ignore_ssl_errors),
                checked = sslBypass,
                summary = context.getString(R.string.ignore_ssl_errors_summary),
                onCheckedChange = {
                    settings.isSSLBypassEnabled = it
                    if (it) {
                        showRestartRequired()
                    }
                },
            )
        },
        offlineCheck = {
            SettingsSwitchPreference(
                title = context.getString(R.string.disable_connectivity_check),
                checked = offlineDisabled,
                summary = context.getString(R.string.disable_connectivity_check_summary),
                onCheckedChange = { settings.isOfflineCheckDisabled = it },
            )
        },
        adBlock = {
            SettingsSwitchPreference(
                title = context.getString(R.string.adblock),
                checked = adBlock,
                summary = context.getString(R.string.adblock_summary),
                onCheckedChange = { settings.isAdBlockEnabled = it },
            )
        },
        dataRemoval = {
            SettingsGroupLabel(text = context.getString(R.string.local_storage))
            SettingsActionPreference(
                title = context.getString(R.string.clear_local_manga_storage),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.LOCAL_MANGA),
                enabled = AppSettings.KEY_LOCAL_MANGA_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = onConfirmClearLocalManga,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_local_novel_storage),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.LOCAL_NOVELS),
                enabled = AppSettings.KEY_LOCAL_NOVELS_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = onConfirmClearLocalNovels,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_local_video_storage),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.LOCAL_VIDEOS),
                enabled = AppSettings.KEY_LOCAL_VIDEOS_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = onConfirmClearLocalVideos,
            )
            SettingsSectionDivider()
            SettingsGroupLabel(text = context.getString(R.string.cache))
            SettingsActionPreference(
                title = context.getString(R.string.clear_thumbs_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.THUMBS_CACHE),
                enabled = AppSettings.KEY_THUMBS_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_THUMBS_CACHE_CLEAR, CacheDir.THUMBS)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_favicons_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.FAVICONS_CACHE),
                enabled = AppSettings.KEY_FAVICONS_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_FAVICONS_CACHE_CLEAR, CacheDir.FAVICONS)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_pages_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.PAGES_CACHE),
                enabled = AppSettings.KEY_PAGES_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_PAGES_CACHE_CLEAR, CacheDir.PAGES)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_novel_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.NOVELS_CACHE),
                enabled = AppSettings.KEY_NOVEL_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_NOVEL_CACHE_CLEAR, CacheDir.NOVELS)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_video_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.VIDEO_CACHE),
                enabled = AppSettings.KEY_VIDEO_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_VIDEO_CACHE_CLEAR, CacheDir.VIDEO)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_video_proxy_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.VIDEO_PROXY_CACHE),
                enabled = AppSettings.KEY_VIDEO_PROXY_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_VIDEO_PROXY_CACHE_CLEAR, CacheDir.VIDEO_PROXY)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_danmaku_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.DANMAKU_CACHE),
                enabled = AppSettings.KEY_VIDEO_DANMAKU_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_VIDEO_DANMAKU_CACHE_CLEAR, CacheDir.DANMAKU)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_tts_audio_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.TTS_CACHE),
                enabled = AppSettings.KEY_TTS_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_TTS_CACHE_CLEAR, CacheDir.TtsAudio)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.reader_super_resolution_clear_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.SUPER_RESOLUTION_CACHE),
                enabled = AppSettings.KEY_SR_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = {
                    dataCleanupViewModel.clearCache(AppSettings.KEY_SR_CACHE_CLEAR, CacheDir.SUPER_RESOLUTION)
                },
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_network_cache),
                summary = storageSummary(context, storageUsage, StorageUsageCategory.HTTP_CACHE),
                enabled = AppSettings.KEY_HTTP_CACHE_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = dataCleanupViewModel::clearHttpCache,
            )
            SettingsSectionDivider()
            SettingsGroupLabel(text = context.getString(R.string.privacy))
            SettingsActionPreference(
                title = context.getString(R.string.clear_search_history),
                summary = countSummary(context, searchHistoryCount),
                enabled = AppSettings.KEY_SEARCH_HISTORY_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = onConfirmClearSearchHistory,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_updates_feed),
                summary = countSummary(context, feedItemsCount),
                enabled = AppSettings.KEY_UPDATES_FEED_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = dataCleanupViewModel::clearUpdatesFeed,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_database),
                summary = context.getString(R.string.clear_database_summary),
                enabled = AppSettings.KEY_CLEAR_MANGA_DATA !in loadingKeys,
                showChevron = false,
                onClick = dataCleanupViewModel::clearContentData,
            )
            SettingsSectionDivider()
            SettingsActionPreference(
                title = context.getString(R.string.clear_cookies),
                summary = context.getString(R.string.clear_cookies_summary),
                showChevron = false,
                onClick = onConfirmClearCookies,
            )
            if (dataCleanupViewModel.isBrowserDataCleanupEnabled) {
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = context.getString(R.string.clear_browser_data),
                    summary = context.getString(R.string.clear_browser_data_summary),
                    enabled = AppSettings.KEY_WEBVIEW_CLEAR !in loadingKeys,
                    showChevron = false,
                    onClick = dataCleanupViewModel::clearBrowserData,
                )
            }
            SettingsSectionDivider()
            SettingsGroupLabel(text = context.getString(R.string.chapters))
            SettingsActionPreference(
                title = context.getString(R.string.delete_read_chapters),
                summary = context.getString(R.string.delete_read_chapters_summary),
                enabled = AppSettings.KEY_CHAPTERS_CLEAR !in loadingKeys,
                showChevron = false,
                onClick = onConfirmCleanupChapters,
            )
            SettingsSectionDivider()
            SettingsSwitchPreference(
                title = context.getString(R.string.delete_read_chapters_auto),
                summary = context.getString(R.string.runs_on_app_start),
                checked = settings.prefs.getBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, false),
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, checked).apply()
                },
            )
        },
    )
}

@Composable
fun CacheLimitsSettingsRoute(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_CACHE_MB) { videoCacheSizeMb }.value
    val videoProxyCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_PROXY_CACHE_MB) { videoProxyCacheSizeMb }.value
    val videoDanmakuCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_DANMAKU_CACHE_MB) {
        videoDanmakuCacheSizeMb
    }.value
    val thumbsCacheMb = settings.observeAsState(AppSettings.KEY_THUMBS_CACHE_MB) { thumbsCacheSizeMb }.value
    val faviconCacheMb = settings.observeAsState(AppSettings.KEY_FAVICON_CACHE_MB) { faviconCacheSizeMb }.value
    val pagesCacheMb = settings.observeAsState(AppSettings.KEY_PAGES_CACHE_MB) { pagesCacheSizeMb }.value
    val novelCacheMb = settings.observeAsState(AppSettings.KEY_NOVEL_CACHE_MB) { novelCacheSizeMb }.value
    val httpCacheMb = settings.observeAsState(AppSettings.KEY_HTTP_CACHE_MB_LIMIT) { httpCacheSizeMb }.value
    val ttsCacheMb = settings.observeAsState(AppSettings.KEY_TTS_CACHE_MB) { ttsCacheSizeMb }.value
    val srCacheLimit = settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT) {
        settings.prefs.getString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "512") ?: "512"
    }.value
    val srCacheLabels = context.resources.getStringArray(R.array.reader_super_resolution_cache_limits)
    val srCacheValues = context.resources.getStringArray(R.array.values_reader_super_resolution_cache_limits)
    val showRestartRequired = {
        Toast.makeText(context, R.string.settings_apply_restart_required, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
    ) {
        SettingsPreferenceSection(title = context.getString(R.string.image_caches)) {
            SettingsSliderPreference(
                title = context.getString(R.string.thumbnails_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = thumbsCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.thumbsCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.favicons_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = faviconCacheMb,
                valueRange = 4..128,
                step = 4,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.faviconCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.pages_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = pagesCacheMb,
                valueRange = 64..4096,
                step = 64,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.pagesCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.novel_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = novelCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.novelCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.tts_audio_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = ttsCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.ttsCacheSizeMb = it
                    showRestartRequired()
                },
            )
            SettingsSectionDivider()
            SettingsChoicePreference(
                title = context.getString(R.string.reader_super_resolution_cache_limit),
                value = srCacheLimit,
                options = srCacheLabels.mapIndexed { index, label ->
                    SettingsChoiceOption(srCacheValues[index], label)
                },
                onValueChange = {
                    settings.prefs.edit().putString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, it).apply()
                },
            )
        }
        SettingsPreferenceSection(title = context.getString(R.string.video_caches)) {
            SettingsSliderPreference(
                title = context.getString(R.string.video_playback_cache_limit),
                value = videoCacheMb,
                valueRange = 256..4096,
                step = 128,
                valueText = { "$it MB" },
                onValueChange = { settings.videoCacheSizeMb = it },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.video_proxy_cache_limit),
                value = videoProxyCacheMb,
                valueRange = 128..4096,
                step = 128,
                valueText = { "$it MB" },
                onValueChange = { settings.videoProxyCacheSizeMb = it },
            )
            SettingsSectionDivider()
            SettingsSliderPreference(
                title = context.getString(R.string.danmaku_cache_limit),
                value = videoDanmakuCacheMb,
                valueRange = 16..1024,
                step = 16,
                valueText = { "$it MB" },
                onValueChange = { settings.videoDanmakuCacheSizeMb = it },
            )
        }
        SettingsPreferenceSection(title = context.getString(R.string.network)) {
            SettingsSliderPreference(
                title = context.getString(R.string.network_cache_limit),
                summary = context.getString(R.string.cache_limit_applies_on_restart),
                value = httpCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { "$it MB" },
                onValueChange = {
                    settings.httpCacheSizeMb = it
                    showRestartRequired()
                },
            )
        }
    }
}

private fun storageSummary(
    context: android.content.Context,
    storageUsage: StorageUsage?,
    category: StorageUsageCategory,
): String {
    val bytes = storageUsage?.find(category)?.bytes ?: return context.getString(R.string.computing_)
    return FileSize.BYTES.format(context, bytes)
}

private fun countSummary(
    context: android.content.Context,
    count: Int,
): String = when {
    count < 0 -> context.getString(R.string.loading_)
    else -> context.resources.getQuantityStringSafe(R.plurals.items, count, count)
}

private fun buildProxySummary(
    settings: AppSettings,
    context: android.content.Context,
): String {
    val type = settings.proxyType
    val address = settings.proxyAddress
    val port = settings.proxyPort
    return when {
        type == Proxy.Type.DIRECT -> context.getString(R.string.disabled)
        address.isNullOrEmpty() || port == 0 -> context.getString(R.string.invalid_proxy_configuration)
        else -> "$address:$port"
    }
}
