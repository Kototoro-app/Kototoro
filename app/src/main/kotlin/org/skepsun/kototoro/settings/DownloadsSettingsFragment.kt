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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
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
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.resolveFile
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.settings.compose.DownloadsSettingsScreen
import org.skepsun.kototoro.settings.compose.DownloadsSettingsUiState
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.storage.ContentDirectorySelectDialog

@AndroidEntryPoint
class DownloadsSettingsFragment : Fragment() {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var storageManager: LocalStorageManager

    @Inject
    lateinit var downloadsScheduler: DownloadWorker.Scheduler

    private val storageTick = MutableStateFlow(0)
    private val dozeTick = MutableStateFlow(0)

    private val pickFileTreeLauncher = OpenDocumentTreeHelper(this) {
        if (it != null) {
            onDirectoryPicked(it)
        }
    }

    private val ignoreDozeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        dozeTick.update { tick -> tick + 1 }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as ComposeView).setContent {
            KototoroTheme {
                DownloadsSettingsRoute(
                    settings = settings,
                    storageManager = storageManager,
                    storageRefreshKey = storageTick.collectAsStateWithLifecycle().value,
                    dozeRefreshKey = dozeTick.collectAsStateWithLifecycle().value,
                    onOpenMangaDirectories = { router.openDirectoriesSettings() },
                    onOpenMangaStorage = { router.showDirectorySelectDialog() },
                    onOpenNovelStorage = {
                        router.showDirectorySelectDialog(ContentDirectorySelectDialog.CONTENT_TYPE_NOVEL)
                    },
                    onOpenVideoStorage = {
                        router.showDirectorySelectDialog(ContentDirectorySelectDialog.CONTENT_TYPE_VIDEO)
                    },
                    onAllowMeteredNetworkChange = { option ->
                        settings.allowDownloadOnMeteredNetwork = option
                        updateDownloadsConstraints()
                    },
                    onRequestIgnoreDoze = ::startIgnoreDozeActivity,
                    onPickPagesDirectory = { initialUri ->
                        pickFileTreeLauncher.tryLaunch(initialUri)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.downloads))
        storageTick.update { it + 1 }
        dozeTick.update { it + 1 }
    }

    private fun onDirectoryPicked(uri: Uri) {
        storageManager.takePermissions(uri)
        val doc = DocumentFile.fromTreeUri(requireContext(), uri)?.takeIf { it.canWrite() }
        settings.setPagesSaveDir(doc?.uri)
        storageTick.update { it + 1 }
    }

    private fun updateDownloadsConstraints() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val option = when (settings.allowDownloadOnMeteredNetwork) {
                        TriStateOption.ENABLED -> true
                        TriStateOption.ASK -> return@withContext
                        TriStateOption.DISABLED -> false
                    }
                    downloadsScheduler.updateConstraints(option)
                }
            } catch (e: Exception) {
                e.printStackTraceDebug()
            }
        }
    }

    private fun startIgnoreDozeActivity(): Boolean {
        val context = context ?: return false
        return startIgnoreDozeActivity(context, ignoreDozeLauncher)
    }
}

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
        AlertDialog(
            onDismissRequest = {
                showUncappedWarning = false
            },
            title = {
                Text(stringResource(R.string.download_max_active_series_warning_title))
            },
            text = {
                Text(stringResource(R.string.download_max_active_series_warning_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.downloadMaxActiveSeries = AppSettings.UNLIMITED_SERIES
                        showUncappedWarning = false
                    }
                ) {
                    Text(stringResource(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUncappedWarning = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
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
