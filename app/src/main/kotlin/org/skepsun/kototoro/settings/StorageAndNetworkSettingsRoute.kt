package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.core.content.edit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.Proxy
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.DoHProvider
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogMeta
import org.skepsun.kototoro.core.github.GitHubMirrorProbeState
import org.skepsun.kototoro.core.github.GitHubMirrorSyncState
import org.skepsun.kototoro.core.github.latencyLabel
import org.skepsun.kototoro.core.github.mirrorProbeSummary
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.CloudflareStrategy
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.NetworkPolicy
import org.skepsun.kototoro.core.prefs.displayName
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.settings.compose.SettingsActionPreference
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsChoicePreference
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding
import org.skepsun.kototoro.settings.compose.settingsContentTopInset
import org.skepsun.kototoro.settings.compose.SettingsPreferenceGroup
import org.skepsun.kototoro.settings.compose.SettingsSliderPreference
import org.skepsun.kototoro.settings.compose.SettingsSwitchPreference
import org.skepsun.kototoro.settings.compose.SettingsTextInputPreference
import org.skepsun.kototoro.settings.compose.StorageAndNetworkSettingsScreen
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage

@Composable
fun StorageAndNetworkSettingsRoute(
    settings: AppSettings,
    viewModel: StorageAndNetworkSettingsViewModel,
    onOpenCacheLimits: () -> Unit,
    onOpenDataRemoval: () -> Unit,
    onOpenProxySettings: () -> Unit,
) {
    val context = LocalContext.current
    val storageUsage = viewModel.storageUsage.collectAsStateWithLifecycle().value

    val prefetchPolicy = settings.observeAsState(AppSettings.KEY_PREFETCH_CONTENT) { contentPrefetchPolicy }.value
    val pagesPreloadPolicy = settings.observeAsState(AppSettings.KEY_PAGES_PRELOAD) { pagesPreloadPolicy }.value
    val dnsOverHttps = settings.observeAsState(AppSettings.KEY_DOH) { dnsOverHttps }.value
    val cloudflareStrategy = settings.observeAsState(AppSettings.KEY_CLOUDFLARE_STRATEGY) {
        cloudflareStrategy
    }.value
    val dohCustomUrl = settings.observeAsState(AppSettings.KEY_DOH_CUSTOM_URL) { dohCustomUrl.orEmpty() }.value
    val dohCustomIps = settings.observeAsState(AppSettings.KEY_DOH_CUSTOM_IPS) { dohCustomIps.orEmpty() }.value
    val imagesProxy = settings.observeAsState(AppSettings.KEY_IMAGES_PROXY) { imagesProxy }.value
    val gitHubMirrorId = settings.observeAsState(AppSettings.KEY_GITHUB_MIRROR) { gitHubMirrorId }.value
    val huggingFaceMirror = settings.observeAsState(AppSettings.KEY_HUGGINGFACE_MIRROR) { huggingFaceMirror }.value
    val bangumiMirror = settings.observeAsState(AppSettings.KEY_BANGUMI_MIRROR) { bangumiMirror }.value
    val bangumiMirrorCustomBase = settings.observeAsState(AppSettings.KEY_BANGUMI_MIRROR_CUSTOM_BASE) {
        bangumiMirrorCustomBase.orEmpty()
    }.value
    val bangumiMirrorCustomApiBase = settings.observeAsState(AppSettings.KEY_BANGUMI_MIRROR_CUSTOM_API_BASE) {
        bangumiMirrorCustomApiBase.orEmpty()
    }.value
    val sslBypass = settings.observeAsState(AppSettings.KEY_SSL_BYPASS) { isSSLBypassEnabled }.value
    val offlineDisabled = settings.observeAsState(AppSettings.KEY_OFFLINE_DISABLED) { isOfflineCheckDisabled }.value
    val adBlock = settings.observeAsState(AppSettings.KEY_ADBLOCK) { isAdBlockEnabled }.value

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val dohLabels = context.resources.getStringArray(R.array.doh_providers)
    val imageProxyLabels = context.resources.getStringArray(R.array.image_proxies)
    val cloudflareStrategyLabels = context.resources.getStringArray(R.array.cloudflare_strategies)
    val cloudflareStrategyOptions = CloudflareStrategy.entries.mapIndexed { index, strategy ->
        SettingsChoiceOption(strategy, cloudflareStrategyLabels.getOrElse(index) { strategy.name })
    }

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

    val githubMirrorSyncState = viewModel.mirrorSyncState.collectAsStateWithLifecycle().value
    val githubMirrorSyncMeta = viewModel.mirrorCatalogMeta.collectAsStateWithLifecycle().value
    val githubMirrorProbeState = viewModel.mirrorProbeState.collectAsStateWithLifecycle().value
    val githubMirrorProbeResults = viewModel.mirrorProbeResults.collectAsStateWithLifecycle().value
    val githubMirrorEntries = viewModel.mirrorEntries.collectAsStateWithLifecycle().value
    val githubMirrorSyncUrl = settings.observeAsState(AppSettings.KEY_GITHUB_MIRROR_SYNC_URL) {
        githubMirrorSyncUrl.orEmpty()
    }.value

    LaunchedEffect(githubMirrorSyncState) {
        when (val sync = githubMirrorSyncState) {
            is GitHubMirrorSyncState.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.mirror_sync_success, sync.version, sync.mirrorCount),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            is GitHubMirrorSyncState.Failed -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.mirror_sync_failed, sync.error.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
            }
            // Nothing published for this branch is a known state, not an error:
            // reporting it as a failure made a working feature look broken.
            is GitHubMirrorSyncState.NoManifest -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.mirror_sync_no_manifest),
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> Unit
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
    val gitHubMirrorOptions = githubMirrorEntries.map { entry ->
        SettingsChoiceOption(entry.id, entry.displayName(context) + githubMirrorProbeResults[entry.id].latencyLabel(context))
    }
    val huggingFaceMirrorOptions = listOf(
        SettingsChoiceOption(AppSettings.HuggingFaceMirror.NATIVE, "Direct Native (Default)"),
        SettingsChoiceOption(AppSettings.HuggingFaceMirror.HF_MIRROR, "hf-mirror.com"),
    )
    val bangumiMirrorOptions = listOf(
        SettingsChoiceOption(AppSettings.BangumiMirror.NATIVE, "Official (Default)"),
        SettingsChoiceOption(AppSettings.BangumiMirror.BANGUMI_LOL, "bangumi.pro"),
        SettingsChoiceOption(AppSettings.BangumiMirror.CUSTOM, "Custom"),
    )

    val totalCacheLimitMb = settings.observeAsState(AppSettings.KEY_THUMBS_CACHE_MB) { thumbsCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_FAVICON_CACHE_MB) { faviconCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_PAGES_CACHE_MB) { pagesCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_NOVEL_CACHE_MB) { novelCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_TTS_CACHE_MB) { ttsCacheSizeMb }.value +
        (settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT) {
            settings.prefs.getString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "512") ?: "512"
        }.value.toIntOrNull() ?: 512) +
        settings.observeAsState(AppSettings.KEY_VIDEO_CACHE_MB) { videoCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_VIDEO_PROXY_CACHE_MB) { videoProxyCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_TORRENT_CACHE_MB) { torrentCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_VIDEO_DANMAKU_CACHE_MB) { videoDanmakuCacheSizeMb }.value +
        settings.observeAsState(AppSettings.KEY_HTTP_CACHE_MB_LIMIT) { httpCacheSizeMb }.value

    val cacheLimitsSummary = context.getString(
        R.string.cache_limits_total_quota,
        formatCacheLimitMb(totalCacheLimitMb),
    )

    StorageAndNetworkSettingsScreen(
        storageTitle = context.getString(R.string.storage_usage),
        cacheLimitsTitle = context.getString(R.string.cache_limits),
        cacheLimitsSummary = cacheLimitsSummary,
        dataRemovalTitle = context.getString(R.string.data_removal),
        networkTitle = context.getString(R.string.network),
        proxyMirrorsTitle = context.getString(R.string.network_group_proxy_mirrors),
        securityTitle = context.getString(R.string.network_group_security),
        storageUsage = storageUsage,
        snackbarHostState = snackbarHostState,
        onCacheLimitsClick = onOpenCacheLimits,
        onDataRemovalClick = onOpenDataRemoval,
        prefetchContent = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.prefetch_content),
                    iconRes = R.drawable.ic_download,
                    value = prefetchPolicy,
                    options = networkOptions,
                    onValueChange = { settings.contentPrefetchPolicy = it },
                )
            }
        },
        preloadPages = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.preload_pages),
                    iconRes = R.drawable.ic_book_page,
                    value = pagesPreloadPolicy,
                    options = networkOptions,
                    onValueChange = { settings.pagesPreloadPolicy = it },
                )
            }
        },
        proxy = {
            item {
                SettingsActionPreference(
                    title = context.getString(R.string.proxy),
                    iconRes = R.drawable.ic_web,
                    summary = buildProxySummary(settings, context),
                    onClick = onOpenProxySettings,
                )
            }
        },
        dns = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.dns_over_https),
                    iconRes = R.drawable.ic_dns,
                    value = dnsOverHttps,
                    options = dohOptions,
                    onValueChange = { settings.dnsOverHttps = it },
                )
            }
        },
        customDohUrl = {
            if (dnsOverHttps == DoHProvider.CUSTOM) {
                item {
                    SettingsTextInputPreference(
                        title = context.getString(R.string.pref_doh_custom_url),
                        iconRes = R.drawable.ic_web,
                        value = dohCustomUrl,
                        onValueChange = { settings.dohCustomUrl = it },
                    )
                }
            }
        },
        customDohIps = {
            if (dnsOverHttps == DoHProvider.CUSTOM) {
                item {
                    SettingsTextInputPreference(
                        title = context.getString(R.string.pref_doh_custom_ips),
                        iconRes = R.drawable.ic_dns,
                        value = dohCustomIps,
                        onValueChange = { settings.dohCustomIps = it },
                    )
                }
            }
        },
        webViewTransport = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.pref_cloudflare_strategy),
                    iconRes = R.drawable.ic_web,
                    value = cloudflareStrategy,
                    options = cloudflareStrategyOptions,
                    summary = context.getString(R.string.pref_cloudflare_strategy_summary),
                    onValueChange = { settings.cloudflareStrategy = it },
                )
            }
        },
        imageProxy = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.images_proxy_title),
                    iconRes = R.drawable.ic_images,
                    value = imagesProxy,
                    options = imageProxyOptions,
                    onValueChange = { settings.imagesProxy = it },
                )
            }
        },
        githubMirror = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.pref_github_mirror),
                    iconRes = R.drawable.ic_code,
                    value = gitHubMirrorId,
                    options = gitHubMirrorOptions,
                    summary = context.getString(R.string.pref_github_mirror_summary),
                    onValueChange = { settings.gitHubMirrorId = it },
                )
            }
            item {
                val syncing = githubMirrorSyncState is GitHubMirrorSyncState.Refreshing
                SettingsActionPreference(
                    title = context.getString(
                        if (syncing) R.string.mirror_sync_cancel else R.string.mirror_sync_term,
                    ),
                    summary = mirrorSyncSummary(context, githubMirrorSyncState, githubMirrorSyncMeta),
                    iconRes = R.drawable.ic_sync,
                    enabled = true,
                    showChevron = false,
                    onClick = {
                        if (syncing) {
                            viewModel.cancelMirrorCatalogSync()
                        } else {
                            viewModel.refreshMirrorCatalog()
                        }
                    },
                )
            }
            item {
                val probing = githubMirrorProbeState is GitHubMirrorProbeState.Running
                SettingsActionPreference(
                    title = context.getString(
                        if (probing) R.string.mirror_probe_cancel else R.string.mirror_probe_action,
                    ),
                    summary = mirrorProbeSummary(context, githubMirrorProbeState, githubMirrorEntries),
                    iconRes = R.drawable.ic_wifi,
                    enabled = true,
                    showChevron = false,
                    onClick = {
                        if (probing) {
                            viewModel.cancelMirrorProbes()
                        } else {
                            viewModel.probeMirrors()
                        }
                    },
                )
            }
            item {
                SettingsTextInputPreference(
                    title = context.getString(R.string.mirror_sync_url_title),
                    value = githubMirrorSyncUrl,
                    summary = context.getString(R.string.mirror_sync_url_summary),
                    placeholder = context.getString(R.string.mirror_sync_url_default, mirrorSyncDefaultUrl(context)),
                    iconRes = R.drawable.ic_edit,
                    onValueChange = { settings.githubMirrorSyncUrl = it },
                )
            }
        },
        huggingFaceMirror = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.pref_huggingface_mirror),
                    iconRes = R.drawable.ic_face,
                    value = huggingFaceMirror,
                    options = huggingFaceMirrorOptions,
                    summary = context.getString(R.string.pref_huggingface_mirror_summary),
                    onValueChange = { settings.huggingFaceMirror = it },
                )
            }
        },
        bangumiMirror = {
            item {
                SettingsChoicePreference(
                    title = context.getString(R.string.pref_bangumi_mirror),
                    iconRes = R.drawable.ic_content_video,
                    value = bangumiMirror,
                    options = bangumiMirrorOptions,
                    summary = context.getString(R.string.pref_bangumi_mirror_summary),
                    onValueChange = { settings.bangumiMirror = it },
                )
            }
        },
        bangumiMirrorCustomBase = {
            if (bangumiMirror == AppSettings.BangumiMirror.CUSTOM) {
                item {
                    SettingsTextInputPreference(
                        title = context.getString(R.string.pref_bangumi_mirror_custom_base),
                        iconRes = R.drawable.ic_web,
                        value = bangumiMirrorCustomBase,
                        summary = context.getString(R.string.pref_bangumi_mirror_custom_base_summary),
                        placeholder = "https://bangumi.pro",
                        onValueChange = { settings.bangumiMirrorCustomBase = it },
                    )
                }
                item {
                    SettingsTextInputPreference(
                        title = context.getString(R.string.pref_bangumi_mirror_custom_api_base),
                        iconRes = R.drawable.ic_dns,
                        value = bangumiMirrorCustomApiBase,
                        summary = context.getString(R.string.pref_bangumi_mirror_custom_api_base_summary),
                        placeholder = "https://api.bangumi.pro",
                        onValueChange = { settings.bangumiMirrorCustomApiBase = it },
                    )
                }
            }
        },
        sslBypass = {
            item {
                SettingsSwitchPreference(
                    title = context.getString(R.string.ignore_ssl_errors),
                    iconRes = R.drawable.ic_lock_open,
                    checked = sslBypass,
                    summary = context.getString(R.string.ignore_ssl_errors_summary),
                    onCheckedChange = {
                        settings.isSSLBypassEnabled = it
                        if (it) {
                            showRestartRequired()
                        }
                    },
                )
            }
        },
        offlineCheck = {
            item {
                SettingsSwitchPreference(
                    title = context.getString(R.string.disable_connectivity_check),
                    iconRes = R.drawable.ic_offline,
                    checked = offlineDisabled,
                    summary = context.getString(R.string.disable_connectivity_check_summary),
                    onCheckedChange = { settings.isOfflineCheckDisabled = it },
                )
            }
        },
        adBlock = {
            item {
                SettingsSwitchPreference(
                    title = context.getString(R.string.adblock),
                    iconRes = R.drawable.ic_disable,
                    checked = adBlock,
                    summary = context.getString(R.string.adblock_summary),
                    onCheckedChange = { settings.isAdBlockEnabled = it },
                )
            }
        },
    )
}

enum class CacheLimitsPreset {
    COMPACT,
    BALANCED,
    PERFORMANCE,
    CUSTOM,
}

internal fun formatCacheLimitMb(mb: Int): String = when {
    mb >= 1024 && mb % 1024 == 0 -> "${mb / 1024} GB"
    mb >= 1024 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024f)
    else -> "$mb MB"
}

@Composable
private fun CachePresetChip(
    label: String,
    sublabel: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
fun CacheLimitsSettingsRoute(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_CACHE_MB) { videoCacheSizeMb }.value
    val videoProxyCacheMb = settings.observeAsState(AppSettings.KEY_VIDEO_PROXY_CACHE_MB) {
        videoProxyCacheSizeMb
    }.value
    val torrentCacheMb = settings.observeAsState(AppSettings.KEY_TORRENT_CACHE_MB) { torrentCacheSizeMb }.value
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

    val srMb = srCacheLimit.toIntOrNull() ?: 512
    val totalLimitMb = thumbsCacheMb + faviconCacheMb + pagesCacheMb + novelCacheMb +
        ttsCacheMb + srMb + videoCacheMb + videoProxyCacheMb +
        torrentCacheMb + videoDanmakuCacheMb + httpCacheMb

    val activePreset = when {
        thumbsCacheMb == 128 && faviconCacheMb == 4 && pagesCacheMb == 128 && novelCacheMb == 64 &&
            ttsCacheMb == 64 && srCacheLimit == "256" && videoCacheMb == 512 && videoProxyCacheMb == 512 &&
            torrentCacheMb == 1024 && videoDanmakuCacheMb == 32 && httpCacheMb == 128 -> CacheLimitsPreset.COMPACT

        thumbsCacheMb == 256 && faviconCacheMb == 8 && pagesCacheMb == 200 && novelCacheMb == 100 &&
            ttsCacheMb == 100 && srCacheLimit == "512" && videoCacheMb == 1024 && videoProxyCacheMb == 1024 &&
            torrentCacheMb == 4096 && videoDanmakuCacheMb == 64 && httpCacheMb == 250 -> CacheLimitsPreset.BALANCED

        thumbsCacheMb == 512 && faviconCacheMb == 16 && pagesCacheMb == 1024 && novelCacheMb == 256 &&
            ttsCacheMb == 256 && srCacheLimit == "1024" && videoCacheMb == 2048 && videoProxyCacheMb == 2048 &&
            torrentCacheMb == 8192 && videoDanmakuCacheMb == 128 && httpCacheMb == 512 -> CacheLimitsPreset.PERFORMANCE

        else -> CacheLimitsPreset.CUSTOM
    }

    val applyPreset = { preset: CacheLimitsPreset ->
        when (preset) {
            CacheLimitsPreset.COMPACT -> {
                settings.thumbsCacheSizeMb = 128
                settings.faviconCacheSizeMb = 4
                settings.pagesCacheSizeMb = 128
                settings.novelCacheSizeMb = 64
                settings.ttsCacheSizeMb = 64
                settings.prefs.edit().putString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "256").apply()
                settings.videoCacheSizeMb = 512
                settings.videoProxyCacheSizeMb = 512
                settings.torrentCacheSizeMb = 1024
                settings.videoDanmakuCacheSizeMb = 32
                settings.httpCacheSizeMb = 128
            }
            CacheLimitsPreset.BALANCED -> {
                settings.thumbsCacheSizeMb = 256
                settings.faviconCacheSizeMb = 8
                settings.pagesCacheSizeMb = 200
                settings.novelCacheSizeMb = 100
                settings.ttsCacheSizeMb = 100
                settings.prefs.edit().putString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "512").apply()
                settings.videoCacheSizeMb = 1024
                settings.videoProxyCacheSizeMb = 1024
                settings.torrentCacheSizeMb = 4096
                settings.videoDanmakuCacheSizeMb = 64
                settings.httpCacheSizeMb = 250
            }
            CacheLimitsPreset.PERFORMANCE -> {
                settings.thumbsCacheSizeMb = 512
                settings.faviconCacheSizeMb = 16
                settings.pagesCacheSizeMb = 1024
                settings.novelCacheSizeMb = 256
                settings.ttsCacheSizeMb = 256
                settings.prefs.edit().putString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "1024").apply()
                settings.videoCacheSizeMb = 2048
                settings.videoProxyCacheSizeMb = 2048
                settings.torrentCacheSizeMb = 8192
                settings.videoDanmakuCacheSizeMb = 128
                settings.httpCacheSizeMb = 512
            }
            CacheLimitsPreset.CUSTOM -> {}
        }
        showRestartRequired()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = settingsContentTopInset())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cache_limits),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.cache_limits_total_quota,
                                formatCacheLimitMb(totalLimitMb),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = formatCacheLimitMb(totalLimitMb),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.cache_limit_applies_on_restart),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.cache_limits_quick_presets),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CachePresetChip(
                            label = stringResource(R.string.cache_preset_compact),
                            sublabel = "~2.8 GB",
                            isSelected = activePreset == CacheLimitsPreset.COMPACT,
                            onClick = { applyPreset(CacheLimitsPreset.COMPACT) },
                            modifier = Modifier.weight(1f),
                        )
                        CachePresetChip(
                            label = stringResource(R.string.cache_preset_balanced),
                            sublabel = "~7.5 GB",
                            isSelected = activePreset == CacheLimitsPreset.BALANCED,
                            onClick = { applyPreset(CacheLimitsPreset.BALANCED) },
                            modifier = Modifier.weight(1f),
                        )
                        CachePresetChip(
                            label = stringResource(R.string.cache_preset_performance),
                            sublabel = "~16 GB",
                            isSelected = activePreset == CacheLimitsPreset.PERFORMANCE,
                            onClick = { applyPreset(CacheLimitsPreset.PERFORMANCE) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        SettingsPreferenceGroup(title = context.getString(R.string.image_caches)) {
            item { SettingsSliderPreference(
                title = context.getString(R.string.thumbnails_cache_limit),
                iconRes = R.drawable.ic_images,
                value = thumbsCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = {
                    settings.thumbsCacheSizeMb = it
                    showRestartRequired()
                },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.favicons_cache_limit),
                iconRes = R.drawable.ic_web,
                value = faviconCacheMb,
                valueRange = 4..128,
                step = 4,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = {
                    settings.faviconCacheSizeMb = it
                    showRestartRequired()
                },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.pages_cache_limit),
                iconRes = R.drawable.ic_book_page,
                value = pagesCacheMb,
                valueRange = 64..4096,
                step = 64,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = {
                    settings.pagesCacheSizeMb = it
                    showRestartRequired()
                },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.novel_cache_limit),
                iconRes = R.drawable.ic_read,
                value = novelCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = {
                    settings.novelCacheSizeMb = it
                    showRestartRequired()
                },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.tts_audio_cache_limit),
                iconRes = R.drawable.ic_voice_input,
                value = ttsCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = {
                    settings.ttsCacheSizeMb = it
                    showRestartRequired()
                },
            ) }
            item { SettingsChoicePreference(
                title = context.getString(R.string.reader_super_resolution_cache_limit),
                iconRes = R.drawable.ic_zoom_in,
                value = srCacheLimit,
                options = srCacheLabels.mapIndexed { index, label ->
                    SettingsChoiceOption(srCacheValues[index], label)
                },
                onValueChange = {
                    settings.prefs.edit().putString(AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, it).apply()
                },
            ) }
        }
        SettingsPreferenceGroup(title = context.getString(R.string.video_caches)) {
            item { SettingsSliderPreference(
                title = context.getString(R.string.video_playback_cache_limit),
                iconRes = R.drawable.ic_content_video,
                value = videoCacheMb,
                valueRange = 256..4096,
                step = 128,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = { settings.videoCacheSizeMb = it },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.video_proxy_cache_limit),
                iconRes = R.drawable.ic_dns,
                value = videoProxyCacheMb,
                valueRange = 128..4096,
                step = 128,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = { settings.videoProxyCacheSizeMb = it },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.torrent_cache_limit),
                iconRes = R.drawable.ic_network_cellular,
                value = torrentCacheMb,
                valueRange = 512..16384,
                step = 512,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = { settings.torrentCacheSizeMb = it },
            ) }
            item { SettingsSliderPreference(
                title = context.getString(R.string.danmaku_cache_limit),
                iconRes = R.drawable.ic_danmaku,
                value = videoDanmakuCacheMb,
                valueRange = 16..1024,
                step = 16,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = { settings.videoDanmakuCacheSizeMb = it },
            ) }
        }
        SettingsPreferenceGroup(title = context.getString(R.string.network)) {
            item { SettingsSliderPreference(
                title = context.getString(R.string.network_cache_limit),
                iconRes = R.drawable.ic_web,
                value = httpCacheMb,
                valueRange = 32..2048,
                step = 32,
                valueText = { formatCacheLimitMb(it) },
                onValueChange = {
                    settings.httpCacheSizeMb = it
                    showRestartRequired()
                },
            ) }
        }
    }
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

private fun mirrorSyncDefaultUrl(context: android.content.Context): String {
    val repo = context.getString(R.string.github_updates_repo)
    return "https://cdn.jsdmirror.com/gh/$repo@main/docs/github-mirrors.json"
}

private fun mirrorSyncSummary(
    context: android.content.Context,
    state: GitHubMirrorSyncState,
    meta: GitHubMirrorCatalogMeta,
): String = when (state) {
    is GitHubMirrorSyncState.Refreshing -> context.getString(R.string.mirror_sync_in_progress)
    is GitHubMirrorSyncState.Success -> context.getString(R.string.mirror_sync_success, state.version, state.mirrorCount)
    is GitHubMirrorSyncState.Failed -> context.getString(R.string.mirror_sync_failed_summary)
    is GitHubMirrorSyncState.NoManifest -> context.getString(R.string.mirror_sync_no_manifest_summary)
    GitHubMirrorSyncState.Idle -> {
        if (meta.lastRefreshAt > 0L) {
            context.getString(R.string.mirror_sync_last_updated, meta.version.orEmpty())
        } else {
            context.getString(R.string.mirror_sync_never)
        }
    }
}

