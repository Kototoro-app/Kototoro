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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.ui.compose.KototoroSnackbarHost
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsContent
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsRowIcons
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsUiState

data class DownloadsSettingsUiState(
    val mangaDirectoriesSummary: String,
    val preferredDownloadFormat: DownloadFormat,
    val preferredVideoQuality: String,
    val isNovelImagesEnabled: Boolean,
    val allowDownloadOnMeteredNetwork: TriStateOption,
    val isDozeIgnoreVisible: Boolean,
    val pagesDirectorySummary: String,
    val isPagesSavingAskEnabled: Boolean,
    val downloadThreads: Int,
    val downloadMaxActiveSeries: Int,
    val downloadRequestDelayMs: Int,
    val downloadRetryCount: Int,
    val downloadRetryDelayMs: Int,
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
    onPreferredDownloadFormatChange: (DownloadFormat) -> Unit,
    onPreferredVideoQualityChange: (String) -> Unit,
    onNovelImagesChange: (Boolean) -> Unit,
    onAllowMeteredNetworkChange: (TriStateOption) -> Unit,
    onIgnoreDozeClick: () -> Unit,
    onPagesDirectoryClick: () -> Unit,
    onPagesSavingAskChange: (Boolean) -> Unit,
    onDownloadThreadsChange: (Int) -> Unit,
    onDownloadMaxActiveSeriesChange: (Int) -> Unit,
    onDownloadRequestDelayChange: (Int) -> Unit,
    onDownloadRetryCountChange: (Int) -> Unit,
    onDownloadRetryDelayChange: (Int) -> Unit,
) {
    Scaffold(
        snackbarHost = {
            KototoroSnackbarHost(hostState = snackbarHostState)
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
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "downloads") {
                SettingsPreferenceGroup(title = downloadsTitle) {
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.local_content_directories),
                            iconRes = R.drawable.ic_folder_file,
                            summary = state.mangaDirectoriesSummary,
                            onClick = onMangaDirectoriesClick,
                        )
                    }
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.preferred_download_format),
                            iconRes = R.drawable.ic_file_zip,
                            value = state.preferredDownloadFormat,
                            options = downloadFormatOptions,
                            onValueChange = onPreferredDownloadFormatChange,
                        )
                    }
                    item {
                        SettingsTextInputPreference(
                            title = stringResource(R.string.download_video_quality),
                            iconRes = R.drawable.ic_content_video,
                            value = state.preferredVideoQuality,
                            summary = stringResource(R.string.download_video_quality_summary),
                            placeholder = "1080p, 720p, 480p",
                            onValueChange = onPreferredVideoQualityChange,
                        )
                    }
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.download_novel_images),
                            iconRes = R.drawable.ic_images,
                            checked = state.isNovelImagesEnabled,
                            summary = stringResource(R.string.download_novel_images_summary),
                            onCheckedChange = onNovelImagesChange,
                        )
                    }
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.download_over_cellular),
                            iconRes = R.drawable.ic_wifi,
                            value = state.allowDownloadOnMeteredNetwork,
                            options = meteredNetworkOptions,
                            onValueChange = onAllowMeteredNetworkChange,
                        )
                    }
                    item {
                        SettingsInfoPreference(
                            title = stringResource(R.string.downloads),
                            summary = stringResource(R.string.downloads_settings_info),
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                    if (state.isDozeIgnoreVisible) {
                        item {
                            SettingsActionPreference(
                                title = stringResource(R.string.disable_battery_optimization),
                                iconRes = R.drawable.ic_battery_outline,
                                summary = stringResource(R.string.disable_battery_optimization_summary_downloads),
                                onClick = onIgnoreDozeClick,
                            )
                        }
                    }
                }
            }
            item(key = "download_speed") {
                DownloadSpeedSettingsContent(
                    state = DownloadSpeedSettingsUiState(
                        threads = state.downloadThreads,
                        maxActiveSeries = state.downloadMaxActiveSeries,
                        requestDelayMs = state.downloadRequestDelayMs,
                        retryCount = state.downloadRetryCount,
                        retryDelayMs = state.downloadRetryDelayMs,
                    ),
                    onThreadsChange = onDownloadThreadsChange,
                    onMaxActiveSeriesChange = onDownloadMaxActiveSeriesChange,
                    onRequestDelayChange = onDownloadRequestDelayChange,
                    onRetryCountChange = onDownloadRetryCountChange,
                    onRetryDelayChange = onDownloadRetryDelayChange,
                    icons = DownloadSpeedSettingsRowIcons(
                        threads = painterResource(R.drawable.ic_network_cellular),
                        maxActiveSeries = painterResource(R.drawable.ic_list_group),
                        requestDelay = painterResource(R.drawable.ic_schedule),
                        retryCount = painterResource(R.drawable.ic_retry),
                        retryDelay = painterResource(R.drawable.ic_schedule),
                    ),
                )
            }
            item(key = "pages_saving") {
                SettingsPreferenceGroup(title = pagesSavingTitle) {
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.default_page_save_dir),
                            iconRes = R.drawable.ic_folder_file,
                            summary = state.pagesDirectorySummary,
                            onClick = onPagesDirectoryClick,
                        )
                    }
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.ask_for_dest_dir_every_time),
                            iconRes = R.drawable.ic_data_privacy,
                            checked = state.isPagesSavingAskEnabled,
                            onCheckedChange = onPagesSavingAskChange,
                        )
                    }
                }
            }
        }
    }
}
