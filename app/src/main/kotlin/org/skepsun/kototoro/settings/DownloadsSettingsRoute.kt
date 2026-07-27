package org.skepsun.kototoro.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.resolveFile
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.settings.compose.DownloadsSettingsScreen
import org.skepsun.kototoro.settings.compose.DownloadsSettingsUiState
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption

@Composable
fun DownloadsSettingsRoute(
    settings: AppSettings,
    storageManager: LocalStorageManager,
    storageRefreshKey: Int,
    dozeRefreshKey: Int,
    onOpenMangaDirectories: () -> Unit,
    onOpenMangaStorage: () -> Unit,
    onOpenNovelStorage: () -> Unit,
    onOpenVideoStorage: () -> Unit,
    onAllowMeteredNetworkChange: (TriStateOption) -> Unit,
    onRequestIgnoreDoze: () -> Boolean,
    onPickPagesDirectory: (Uri?) -> Boolean,
) {
    val context = LocalContext.current
    val preferredDownloadFormat =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_FORMAT) { preferredDownloadFormat }.value
    val isDownloadAlignedWithReader =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_ALIGN_READER) { isDownloadAlignedWithReader }.value
    val isDownloadAutoRetryOnNetworkError =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_AUTO_RETRY) { isDownloadAutoRetryOnNetworkError }.value
    val downloadThreads = settings.observeAsState(AppSettings.KEY_DOWNLOADS_THREADS) { downloadThreads }.value
    val downloadMaxActiveSeries =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_MAX_ACTIVE_SERIES) { downloadMaxActiveSeries }.value
    var showUncappedWarning by remember { mutableStateOf(false) }
    val downloadRequestDelayMs =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_REQUEST_DELAY) { downloadRequestDelayMs }.value
    val downloadRetryCount =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_RETRY_COUNT) { downloadRetryCount }.value
    val downloadRetryDelayMs =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_RETRY_DELAY) { downloadRetryDelayMs }.value
    val allowDownloadOnMeteredNetwork =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_METERED_NETWORK) { allowDownloadOnMeteredNetwork }.value
    val pagesSaveDirKey =
        settings.observeAsState(AppSettings.KEY_PAGES_SAVE_DIR) { getPagesSaveDir(context)?.uri?.toString() }.value
    val isPagesSavingAskEnabled =
        settings.observeAsState(AppSettings.KEY_PAGES_SAVE_ASK) { isPagesSavingAskEnabled }.value
    val mangaDirectoriesSummary = rememberMangaDirectoriesSummary(storageManager, storageRefreshKey)
    val mangaStorageSummary = rememberStorageSummary(storageRefreshKey) {
        loadStorageSummary(context, storageManager.getDefaultWriteableDir(), storageManager)
    }
    val novelStorageSummary = rememberStorageSummary(storageRefreshKey) {
        loadStorageSummary(context, storageManager.getDefaultNovelWriteableDir(), storageManager)
    }
    val videoStorageSummary = rememberStorageSummary(storageRefreshKey) {
        loadStorageSummary(context, storageManager.getDefaultVideoWriteableDir(), storageManager)
    }
    val pagesDirectorySummary = rememberPagesDirectorySummary(storageRefreshKey, pagesSaveDirKey, settings)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val downloadFormatOptions = listOf(
        SettingsChoiceOption(DownloadFormat.AUTOMATIC, context.getString(R.string.automatic)),
        SettingsChoiceOption(DownloadFormat.SINGLE_CBZ, context.getString(R.string.single_cbz_file)),
        SettingsChoiceOption(DownloadFormat.MULTIPLE_CBZ, context.getString(R.string.multiple_cbz_files)),
    )
    val meteredNetworkOptions = listOf(
        SettingsChoiceOption(TriStateOption.ENABLED, context.getString(R.string.allow_always)),
        SettingsChoiceOption(TriStateOption.ASK, context.getString(R.string.ask_every_time)),
        SettingsChoiceOption(TriStateOption.DISABLED, context.getString(R.string.dont_allow)),
    )

    val state = DownloadsSettingsUiState(
        mangaDirectoriesSummary = mangaDirectoriesSummary,
        mangaStorageSummary = mangaStorageSummary,
        novelStorageSummary = novelStorageSummary,
        videoStorageSummary = videoStorageSummary,
        preferredDownloadFormat = preferredDownloadFormat,
        isDownloadAlignedWithReader = isDownloadAlignedWithReader,
        isDownloadAutoRetryOnNetworkError = isDownloadAutoRetryOnNetworkError,
        downloadThreads = downloadThreads,
        downloadMaxActiveSeries = downloadMaxActiveSeries,
        downloadRequestDelayMs = downloadRequestDelayMs,
        downloadRetryCount = downloadRetryCount,
        downloadRetryDelayMs = downloadRetryDelayMs,
        allowDownloadOnMeteredNetwork = allowDownloadOnMeteredNetwork,
        isDozeIgnoreVisible = isDozeIgnoreAvailable(context, dozeRefreshKey),
        pagesDirectorySummary = pagesDirectorySummary,
        isPagesSavingAskEnabled = isPagesSavingAskEnabled,
    )

    if (showUncappedWarning) {
        SettingsAlertDialog(
            title = stringResource(R.string.download_max_active_series_warning_title),
            onDismissRequest = {
                showUncappedWarning = false
            },
            text = {
                Text(stringResource(R.string.download_max_active_series_warning_message))
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.continue_action),
                    onClick = {
                        settings.downloadMaxActiveSeries = AppSettings.UNLIMITED_SERIES
                        showUncappedWarning = false
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = {
                        showUncappedWarning = false
                    },
                )
            },
        )
    }

    DownloadsSettingsScreen(
        downloadsTitle = context.getString(R.string.downloads),
        pagesSavingTitle = context.getString(R.string.pages_saving),
        state = state,
        snackbarHostState = snackbarHostState,
        downloadFormatOptions = downloadFormatOptions,
        meteredNetworkOptions = meteredNetworkOptions,
        onMangaDirectoriesClick = onOpenMangaDirectories,
        onMangaStorageClick = onOpenMangaStorage,
        onNovelStorageClick = onOpenNovelStorage,
        onVideoStorageClick = onOpenVideoStorage,
        onPreferredDownloadFormatChange = { settings.preferredDownloadFormat = it },
        onDownloadAlignReaderChange = { settings.isDownloadAlignedWithReader = it },
        onDownloadAutoRetryChange = { settings.isDownloadAutoRetryOnNetworkError = it },
        onDownloadThreadsChange = { settings.downloadThreads = it },
        onDownloadMaxActiveSeriesChange = { newValue ->
            if (newValue == AppSettings.UNLIMITED_SERIES) {
                if (downloadMaxActiveSeries != AppSettings.UNLIMITED_SERIES) {
                    showUncappedWarning = true
                }
            } else {
                settings.downloadMaxActiveSeries = newValue
            }
        },
        onDownloadRequestDelayChange = { settings.downloadRequestDelayMs = it },
        onDownloadRetryCountChange = { settings.downloadRetryCount = it },
        onDownloadRetryDelayChange = { settings.downloadRetryDelayMs = it },
        onAllowMeteredNetworkChange = onAllowMeteredNetworkChange,
        onIgnoreDozeClick = {
            if (!onRequestIgnoreDoze()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.operation_not_supported))
                }
            }
        },
        onPagesDirectoryClick = {
            if (!onPickPagesDirectory(settings.getPagesSaveDir(context)?.uri)) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.operation_not_supported))
                }
            }
        },
        onPagesSavingAskChange = { settings.isPagesSavingAskEnabled = it },
    )
}

@Composable
private fun rememberMangaDirectoriesSummary(
    storageManager: LocalStorageManager,
    refreshKey: Int,
): String {
    val context = LocalContext.current
    return produceState(
        initialValue = context.getString(R.string.loading_),
        key1 = storageManager,
        key2 = refreshKey,
        key3 = context,
    ) {
        val dirs = storageManager.getReadableDirs().size
        value = context.resources.getQuantityStringSafe(R.plurals.items, dirs, dirs)
    }.value
}

@Composable
private fun rememberStorageSummary(
    refreshKey: Int,
    loader: suspend () -> String,
): String {
    val context = LocalContext.current
    return produceState(
        initialValue = context.getString(R.string.loading_),
        key1 = refreshKey,
        producer = {
            value = loader()
        },
    ).value
}

@Composable
private fun rememberPagesDirectorySummary(
    refreshKey: Int,
    pagesSaveDirKey: String?,
    settings: AppSettings,
): String {
    val context = LocalContext.current
    return produceState(
        initialValue = context.getString(androidx.preference.R.string.not_set),
        key1 = refreshKey,
        key2 = pagesSaveDirKey,
        key3 = context,
    ) {
        value = withContext(Dispatchers.IO) {
            settings.getPagesSaveDir(context)
        }?.getDisplayPath(context) ?: context.getString(androidx.preference.R.string.not_set)
    }.value
}

private suspend fun loadStorageSummary(
    context: Context,
    storage: File?,
    storageManager: LocalStorageManager,
): String {
    return if (storage != null) {
        storageManager.getDirectoryDisplayName(storage, isFullPath = true)
    } else {
        context.getString(R.string.not_set)
    }
}

fun startIgnoreDozeActivity(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
): Boolean {
    val packageName = context.packageName
    val powerManager = context.powerManager ?: return false
    if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
        return false
    }
    return try {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri(),
        )
        launcher.launch(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun isDozeIgnoreAvailable(
    context: Context,
    refreshKey: Int,
): Boolean {
    refreshKey
    val powerManager = context.powerManager ?: return false
    return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun DocumentFile.getDisplayPath(context: Context): String {
    return uri.resolveFile(context)?.path ?: uri.toString()
}
