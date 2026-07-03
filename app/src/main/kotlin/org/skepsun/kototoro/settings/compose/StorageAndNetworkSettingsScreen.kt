package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory

private data class StorageUsageUiItem(
    val label: String,
    val bytes: Long,
    val progress: Float,
)

@Composable
fun StorageAndNetworkSettingsScreen(
    storageTitle: String,
    cacheLimitsTitle: String,
    dataRemovalTitle: String,
    networkTitle: String,
    storageUsage: StorageUsage?,
    onCacheLimitsClick: () -> Unit,
    onDataRemovalClick: () -> Unit,
    cacheLimits: @Composable () -> Unit = {},
    prefetchContent: @Composable () -> Unit,
    preloadPages: @Composable () -> Unit,
    proxy: @Composable () -> Unit,
    dns: @Composable () -> Unit,
    customDohUrl: @Composable () -> Unit,
    customDohIps: @Composable () -> Unit,
    imageProxy: @Composable () -> Unit,
    githubMirror: @Composable () -> Unit,
    huggingFaceMirror: @Composable () -> Unit,
    bangumiMirror: @Composable () -> Unit,
    bangumiMirrorCustomBase: @Composable () -> Unit,
    sslBypass: @Composable () -> Unit,
    offlineCheck: @Composable () -> Unit,
    adBlock: @Composable () -> Unit,
    dataRemoval: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "storage_usage") {
                SettingsPreferenceSection(title = storageTitle) {
                    StorageUsageBlock(storageUsage = storageUsage)
                }
            }
            item(key = "cache_limits") {
                SettingsPreferenceSection(title = cacheLimitsTitle) {
                    SettingsActionPreference(
                        title = cacheLimitsTitle,
                        summary = LocalContext.current.getString(R.string.cache_limit_applies_on_restart),
                        onClick = onCacheLimitsClick,
                    )
                }
            }
            item(key = "data_removal") {
                SettingsPreferenceSection(title = dataRemovalTitle) {
                    SettingsActionPreference(
                        title = dataRemovalTitle,
                        onClick = onDataRemovalClick,
                    )
                }
            }
            item(key = "network") {
                SettingsPreferenceSection(title = networkTitle) {
                    prefetchContent()
                    SettingsSectionDivider()
                    preloadPages()
                    SettingsSectionDivider()
                    proxy()
                    SettingsSectionDivider()
                    dns()
                    customDohUrl()
                    customDohIps()
                    SettingsSectionDivider()
                    imageProxy()
                    SettingsSectionDivider()
                    githubMirror()
                    SettingsSectionDivider()
                    huggingFaceMirror()
                    SettingsSectionDivider()
                    bangumiMirror()
                    bangumiMirrorCustomBase()
                    SettingsSectionDivider()
                    sslBypass()
                    SettingsSectionDivider()
                    offlineCheck()
                    SettingsSectionDivider()
                    adBlock()
                }
            }
        }
    }
}

@Composable
private fun StorageUsageBlock(
    storageUsage: StorageUsage?,
) {
    val context = LocalContext.current
    val items = remember(storageUsage, context) {
        storageUsage?.items
            ?.filter { it.bytes > 0L || it.category == StorageUsageCategory.AVAILABLE }
            ?.map {
                StorageUsageUiItem(
                    label = storageCategoryLabel(context, it.category),
                    bytes = it.bytes,
                    progress = it.percent,
                )
            }
            .orEmpty()
    }

    if (items.isEmpty()) {
        Text(
            text = context.getString(R.string.computing_),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        return
    }

    items.forEachIndexed { index, item ->
        Text(
            text = context.getString(
                R.string.memory_usage_pattern,
                FileSize.BYTES.format(context, item.bytes),
                item.label,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LinearProgressIndicator(
            progress = { item.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        if (index != items.lastIndex) {
            SettingsSectionDivider()
        }
    }
}

private fun storageCategoryLabel(
    context: android.content.Context,
    category: StorageUsageCategory,
): String = when (category) {
    StorageUsageCategory.LOCAL_MANGA -> context.getString(R.string.local_manga_storage)
    StorageUsageCategory.LOCAL_NOVELS -> context.getString(R.string.local_novel_storage)
    StorageUsageCategory.LOCAL_VIDEOS -> context.getString(R.string.local_video_storage)
    StorageUsageCategory.THUMBS_CACHE -> context.getString(R.string.thumbnails_cache)
    StorageUsageCategory.FAVICONS_CACHE -> context.getString(R.string.favicons_cache)
    StorageUsageCategory.PAGES_CACHE -> context.getString(R.string.pages_cache)
    StorageUsageCategory.NOVELS_CACHE -> context.getString(R.string.novel_reader_cache)
    StorageUsageCategory.VIDEO_CACHE -> context.getString(R.string.video_playback_cache)
    StorageUsageCategory.VIDEO_PROXY_CACHE -> context.getString(R.string.video_proxy_cache)
    StorageUsageCategory.DANMAKU_CACHE -> context.getString(R.string.danmaku_cache)
    StorageUsageCategory.TTS_CACHE -> context.getString(R.string.tts_audio_cache)
    StorageUsageCategory.SUPER_RESOLUTION_CACHE -> context.getString(R.string.reader_super_resolution_cache)
    StorageUsageCategory.HTTP_CACHE -> context.getString(R.string.network_cache)
    StorageUsageCategory.AI_MODELS -> context.getString(R.string.ai_local_models)
    StorageUsageCategory.OTHER_CACHE -> context.getString(R.string.other_cache)
    StorageUsageCategory.AVAILABLE -> context.getString(R.string.available)
}
