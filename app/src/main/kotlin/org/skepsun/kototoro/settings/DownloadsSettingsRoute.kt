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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
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
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.resolveFile
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.settings.compose.DownloadsSettingsScreen
import org.skepsun.kototoro.settings.compose.DownloadsSettingsUiState
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

@Composable
fun DownloadsSettingsRoute(
    settings: AppSettings,
    storageManager: LocalStorageManager,
    storageRefreshKey: Int,
    dozeRefreshKey: Int,
    onOpenMangaDirectories: () -> Unit,
    onAllowMeteredNetworkChange: (TriStateOption) -> Unit,
    onRequestIgnoreDoze: () -> Boolean,
    onPickPagesDirectory: (Uri?) -> Boolean,
) {
    val context = LocalContext.current
    val preferredDownloadFormat =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_FORMAT) { preferredDownloadFormat }.value
    val preferredVideoQuality =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_VIDEO_QUALITY) { preferredDownloadVideoQuality }.value
    val isNovelImagesEnabled =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_NOVEL_IMAGES) { isDownloadNovelImagesEnabled }.value
    val allowDownloadOnMeteredNetwork =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_METERED_NETWORK) { allowDownloadOnMeteredNetwork }.value
    val pagesSaveDirKey =
        settings.observeAsState(AppSettings.KEY_PAGES_SAVE_DIR) { getPagesSaveDir(context)?.uri?.toString() }.value
    val isPagesSavingAskEnabled =
        settings.observeAsState(AppSettings.KEY_PAGES_SAVE_ASK) { isPagesSavingAskEnabled }.value
    val downloadThreads = settings.observeAsState(AppSettings.KEY_DOWNLOADS_THREADS) { downloadThreads }.value
    val downloadMaxActiveSeries =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_MAX_ACTIVE_SERIES) { downloadMaxActiveSeries }.value
    val downloadRequestDelayMs =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_REQUEST_DELAY) { downloadRequestDelayMs }.value
    val downloadRetryCount =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_RETRY_COUNT) { downloadRetryCount }.value
    val downloadRetryDelayMs =
        settings.observeAsState(AppSettings.KEY_DOWNLOADS_RETRY_DELAY) { downloadRetryDelayMs }.value
    var showUncappedWarning by remember { mutableStateOf(false) }
    val mangaDirectoriesSummary = rememberMangaDirectoriesSummary(storageManager, storageRefreshKey)
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
        preferredDownloadFormat = preferredDownloadFormat,
        preferredVideoQuality = preferredVideoQuality,
        isNovelImagesEnabled = isNovelImagesEnabled,
        allowDownloadOnMeteredNetwork = allowDownloadOnMeteredNetwork,
        isDozeIgnoreVisible = isDozeIgnoreAvailable(context, dozeRefreshKey),
        pagesDirectorySummary = pagesDirectorySummary,
        isPagesSavingAskEnabled = isPagesSavingAskEnabled,
        downloadThreads = downloadThreads,
        downloadMaxActiveSeries = downloadMaxActiveSeries,
        downloadRequestDelayMs = downloadRequestDelayMs,
        downloadRetryCount = downloadRetryCount,
        downloadRetryDelayMs = downloadRetryDelayMs,
    )

    if (showUncappedWarning) {
        SettingsAlertDialog(
            title = stringResource(R.string.download_max_active_series_warning_title),
            onDismissRequest = { showUncappedWarning = false },
            text = { Text(stringResource(R.string.download_max_active_series_warning_message)) },
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
                    onClick = { showUncappedWarning = false },
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
        onPreferredDownloadFormatChange = { settings.preferredDownloadFormat = it },
        onPreferredVideoQualityChange = { value ->
            settings.preferredDownloadVideoQuality = value
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString(", ")
                .ifBlank { "1080p, 720p, 480p" }
        },
        onNovelImagesChange = { settings.isDownloadNovelImagesEnabled = it },
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
        onDownloadThreadsChange = { settings.downloadThreads = it },
        onDownloadMaxActiveSeriesChange = { newValue ->
            if (newValue != AppSettings.UNLIMITED_SERIES) {
                settings.downloadMaxActiveSeries = newValue
            } else if (downloadMaxActiveSeries != AppSettings.UNLIMITED_SERIES) {
                // Uncapped is opt-in behind a warning; the slider snaps back until confirmed.
                showUncappedWarning = true
            }
        },
        onDownloadRequestDelayChange = { settings.downloadRequestDelayMs = it },
        onDownloadRetryCountChange = { settings.downloadRetryCount = it },
        onDownloadRetryDelayChange = { settings.downloadRetryDelayMs = it },
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
        val dirs = storageManager.getAllReadableRoots().size
        value = context.resources.getQuantityStringSafe(R.plurals.items, dirs, dirs)
    }.value
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
