package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

internal fun visibleStorageUsageItems(storageUsage: StorageUsage?): List<StorageUsage.Item> =
    storageUsage?.items
        ?.filter { it.bytes > 0L || it.category == StorageUsageCategory.AVAILABLE }
        .orEmpty()

@Composable
fun StorageAndNetworkSettingsScreen(
    storageTitle: String,
    cacheLimitsTitle: String,
    dataRemovalTitle: String,
    networkTitle: String,
    storageUsage: StorageUsage?,
    onCacheLimitsClick: () -> Unit,
    onDataRemovalClick: () -> Unit,
    prefetchContent: @Composable SettingsItemGroupScope.() -> Unit,
    preloadPages: @Composable SettingsItemGroupScope.() -> Unit,
    proxy: @Composable SettingsItemGroupScope.() -> Unit,
    dns: @Composable SettingsItemGroupScope.() -> Unit,
    customDohUrl: @Composable SettingsItemGroupScope.() -> Unit,
    customDohIps: @Composable SettingsItemGroupScope.() -> Unit,
    imageProxy: @Composable SettingsItemGroupScope.() -> Unit,
    githubMirror: @Composable SettingsItemGroupScope.() -> Unit,
    huggingFaceMirror: @Composable SettingsItemGroupScope.() -> Unit,
    bangumiMirror: @Composable SettingsItemGroupScope.() -> Unit,
    bangumiMirrorCustomBase: @Composable SettingsItemGroupScope.() -> Unit,
    sslBypass: @Composable SettingsItemGroupScope.() -> Unit,
    offlineCheck: @Composable SettingsItemGroupScope.() -> Unit,
    adBlock: @Composable SettingsItemGroupScope.() -> Unit,
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
                val storageUsageItems = rememberStorageUsageItems(storageUsage)
                SettingsPreferenceGroup(title = storageTitle) {
                    if (storageUsageItems.isEmpty()) {
                        item {
                            StorageUsageLoadingRow()
                        }
                    } else {
                        storageUsageItems.forEach { usageItem ->
                            item {
                                StorageUsageRow(item = usageItem)
                            }
                        }
                    }
                }
            }
            item(key = "cache_limits") {
                SettingsPreferenceGroup(title = cacheLimitsTitle) {
                    item {
                        SettingsActionPreference(
                            title = cacheLimitsTitle,
                            iconRes = R.drawable.ic_storage,
                            summary = LocalContext.current.getString(R.string.cache_limit_applies_on_restart),
                            onClick = onCacheLimitsClick,
                        )
                    }
                }
            }
            item(key = "data_removal") {
                SettingsPreferenceGroup(title = dataRemovalTitle) {
                    item {
                        SettingsActionPreference(
                            title = dataRemovalTitle,
                            iconRes = R.drawable.ic_delete_all,
                            onClick = onDataRemovalClick,
                        )
                    }
                }
            }
            item(key = "network") {
                SettingsPreferenceGroup(title = networkTitle) {
                    prefetchContent()
                    preloadPages()
                    proxy()
                    dns()
                    customDohUrl()
                    customDohIps()
                    imageProxy()
                    githubMirror()
                    huggingFaceMirror()
                    bangumiMirror()
                    bangumiMirrorCustomBase()
                    sslBypass()
                    offlineCheck()
                    adBlock()
                }
            }
        }
    }
}

@Composable
private fun rememberStorageUsageItems(
    storageUsage: StorageUsage?,
): List<StorageUsageUiItem> {
    val context = LocalContext.current
    return remember(storageUsage, context) {
        visibleStorageUsageItems(storageUsage)
            .map {
                StorageUsageUiItem(
                    label = storageCategoryLabel(context, it.category),
                    bytes = it.bytes,
                    progress = it.percent,
                )
            }
    }
}

@Composable
private fun StorageUsageLoadingRow() {
    Text(
        text = LocalContext.current.getString(R.string.computing_),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun StorageUsageRow(
    item: StorageUsageUiItem,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = context.getString(
                R.string.memory_usage_pattern,
                FileSize.BYTES.format(context, item.bytes),
                item.label,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { item.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
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
    StorageUsageCategory.TORRENT_CACHE -> context.getString(R.string.torrent_cache)
    StorageUsageCategory.DANMAKU_CACHE -> context.getString(R.string.danmaku_cache)
    StorageUsageCategory.TTS_CACHE -> context.getString(R.string.tts_audio_cache)
    StorageUsageCategory.SUPER_RESOLUTION_CACHE -> context.getString(R.string.reader_super_resolution_cache)
    StorageUsageCategory.HTTP_CACHE -> context.getString(R.string.network_cache)
    StorageUsageCategory.AI_MODELS -> context.getString(R.string.ai_local_models)
    StorageUsageCategory.OTHER_CACHE -> context.getString(R.string.other_cache)
    StorageUsageCategory.AVAILABLE -> context.getString(R.string.available)
}
