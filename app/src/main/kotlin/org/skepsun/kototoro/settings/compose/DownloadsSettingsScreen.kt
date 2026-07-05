package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.prefs.TriStateOption

data class DownloadsSettingsUiState(
    val mangaDirectoriesSummary: String,
    val mangaStorageSummary: String,
    val novelStorageSummary: String,
    val videoStorageSummary: String,
    val preferredDownloadFormat: DownloadFormat,
    val isDownloadAlignedWithReader: Boolean,
    val isDownloadAutoRetryOnNetworkError: Boolean,
    val downloadThreads: Int,
    val downloadMaxActiveSeries: Int,
    val downloadRequestDelayMs: Int,
    val downloadRetryCount: Int,
    val downloadRetryDelayMs: Int,
    val allowDownloadOnMeteredNetwork: TriStateOption,
    val isDozeIgnoreVisible: Boolean,
    val pagesDirectorySummary: String,
    val isPagesSavingAskEnabled: Boolean,
)

@Composable
fun DownloadsSettingsScreen(
    downloadsTitle: String,
    pagesSavingTitle: String,
    state: DownloadsSettingsUiState,
    snackbarHostState: SnackbarHostState,
    downloadFormatOptions: List<SettingsChoiceOption<DownloadFormat>>,
    meteredNetworkOptions: List<SettingsChoiceOption<TriStateOption>>,
    onMangaDirectoriesClick: () -> Unit,
    onMangaStorageClick: () -> Unit,
    onNovelStorageClick: () -> Unit,
    onVideoStorageClick: () -> Unit,
    onPreferredDownloadFormatChange: (DownloadFormat) -> Unit,
    onDownloadAlignReaderChange: (Boolean) -> Unit,
    onDownloadAutoRetryChange: (Boolean) -> Unit,
    onDownloadThreadsChange: (Int) -> Unit,
    onDownloadMaxActiveSeriesChange: (Int) -> Unit,
    onDownloadRequestDelayChange: (Int) -> Unit,
    onDownloadRetryCountChange: (Int) -> Unit,
    onDownloadRetryDelayChange: (Int) -> Unit,
    onAllowMeteredNetworkChange: (TriStateOption) -> Unit,
    onIgnoreDozeClick: () -> Unit,
    onPagesDirectoryClick: () -> Unit,
    onPagesSavingAskChange: (Boolean) -> Unit,
) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "downloads") {
                SettingsPreferenceSection(title = downloadsTitle) {
                    SettingsActionPreference(
                        title = stringResource(R.string.local_manga_directories),
                        summary = state.mangaDirectoriesSummary,
                        onClick = onMangaDirectoriesClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.manga_save_location),
                        summary = state.mangaStorageSummary,
                        onClick = onMangaStorageClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.novel_save_location),
                        summary = state.novelStorageSummary,
                        onClick = onNovelStorageClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.video_save_location),
                        summary = state.videoStorageSummary,
                        onClick = onVideoStorageClick,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.preferred_download_format),
                        value = state.preferredDownloadFormat,
                        options = downloadFormatOptions,
                        onValueChange = onPreferredDownloadFormatChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.download_align_reader),
                        checked = state.isDownloadAlignedWithReader,
                        summary = stringResource(R.string.download_align_reader_summary),
                        onCheckedChange = onDownloadAlignReaderChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.download_auto_retry),
                        checked = state.isDownloadAutoRetryOnNetworkError,
                        summary = stringResource(R.string.download_auto_retry_summary),
                        onCheckedChange = onDownloadAutoRetryChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.download_threads),
                        value = state.downloadThreads,
                        valueRange = 1..10,
                        step = 1,
                        summary = stringResource(R.string.download_threads_summary),
                        valueText = { it.toString() },
                        onValueChange = onDownloadThreadsChange,
                    )
                    SettingsSectionDivider()
                    val uncappedText = stringResource(R.string.download_max_active_series_uncapped)
                    SettingsSliderPreference(
                        title = stringResource(R.string.download_max_active_series),
                        value = state.downloadMaxActiveSeries,
                        valueRange = 1..AppSettings.UNLIMITED_SERIES,
                        step = 1,
                        summary = stringResource(R.string.download_max_active_series_summary),
                        valueText = {
                            if (it == AppSettings.UNLIMITED_SERIES) {
                                uncappedText
                            } else {
                                it.toString()
                            }
                        },
                        onValueChange = onDownloadMaxActiveSeriesChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.download_request_delay),
                        value = state.downloadRequestDelayMs,
                        valueRange = 0..5000,
                        step = 100,
                        summary = stringResource(R.string.download_request_delay_summary),
                        valueText = { "${it} ms" },
                        onValueChange = onDownloadRequestDelayChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.download_retry_count),
                        value = state.downloadRetryCount,
                        valueRange = 1..10,
                        step = 1,
                        summary = stringResource(R.string.download_retry_count_summary),
                        valueText = { it.toString() },
                        onValueChange = onDownloadRetryCountChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.download_retry_delay),
                        value = state.downloadRetryDelayMs,
                        valueRange = 500..10_000,
                        step = 500,
                        summary = stringResource(R.string.download_retry_delay_summary),
                        valueText = { "${it} ms" },
                        onValueChange = onDownloadRetryDelayChange,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.download_over_cellular),
                        value = state.allowDownloadOnMeteredNetwork,
                        options = meteredNetworkOptions,
                        onValueChange = onAllowMeteredNetworkChange,
                    )
                    SettingsSectionDivider()
                    SettingsInfoPreference(
                        title = stringResource(R.string.downloads),
                        summary = stringResource(R.string.downloads_settings_info),
                        iconRes = R.drawable.ic_info_outline,
                    )
                    if (state.isDozeIgnoreVisible) {
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = stringResource(R.string.disable_battery_optimization),
                            summary = stringResource(R.string.disable_battery_optimization_summary_downloads),
                            onClick = onIgnoreDozeClick,
                        )
                    }
                }
            }
            item(key = "pages_saving") {
                SettingsPreferenceSection(title = pagesSavingTitle) {
                    SettingsActionPreference(
                        title = stringResource(R.string.default_page_save_dir),
                        summary = state.pagesDirectorySummary,
                        onClick = onPagesDirectoryClick,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.ask_for_dest_dir_every_time),
                        checked = state.isPagesSavingAskEnabled,
                        onCheckedChange = onPagesSavingAskChange,
                    )
                }
            }
        }
    }
}
