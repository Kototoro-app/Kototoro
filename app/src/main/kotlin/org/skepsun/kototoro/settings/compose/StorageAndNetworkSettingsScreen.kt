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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory

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
    proxyMirrorsTitle: String,
    securityTitle: String,
    storageUsage: StorageUsage?,
    onCacheLimitsClick: () -> Unit,
    onDataRemovalClick: () -> Unit,
    prefetchContent: SettingsItemGroupScope.() -> Unit,
    preloadPages: SettingsItemGroupScope.() -> Unit,
    proxy: SettingsItemGroupScope.() -> Unit,
    dns: SettingsItemGroupScope.() -> Unit,
    customDohUrl: SettingsItemGroupScope.() -> Unit,
    customDohIps: SettingsItemGroupScope.() -> Unit,
    webViewTransport: SettingsItemGroupScope.() -> Unit,
    imageProxy: SettingsItemGroupScope.() -> Unit,
    githubMirror: SettingsItemGroupScope.() -> Unit,
    huggingFaceMirror: SettingsItemGroupScope.() -> Unit,
    bangumiMirror: SettingsItemGroupScope.() -> Unit,
    bangumiMirrorCustomBase: SettingsItemGroupScope.() -> Unit,
    sslBypass: SettingsItemGroupScope.() -> Unit,
    offlineCheck: SettingsItemGroupScope.() -> Unit,
    adBlock: SettingsItemGroupScope.() -> Unit,
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
                top = settingsContentTopInset(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "storage_usage") {
                SettingsPreferenceGroup(title = storageTitle) {
                    if (storageUsage == null) {
                        item {
                            StorageUsageLoadingRow()
                        }
                    } else {
                        item {
                            StorageUsageChartSection(storageUsage = storageUsage)
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
                    dns()
                    customDohUrl()
                    customDohIps()
                    webViewTransport()
                }
            }
            item(key = "proxy_mirrors") {
                SettingsPreferenceGroup(title = proxyMirrorsTitle) {
                    proxy()
                    imageProxy()
                    githubMirror()
                    huggingFaceMirror()
                    bangumiMirror()
                    bangumiMirrorCustomBase()
                }
            }
            item(key = "security") {
                SettingsPreferenceGroup(title = securityTitle) {
                    sslBypass()
                    offlineCheck()
                    adBlock()
                }
            }
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
