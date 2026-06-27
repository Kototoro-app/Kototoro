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
import org.skepsun.kototoro.core.prefs.TrackerDownloadStrategy

data class TrackerSettingsUiState(
    val isTrackerEnabled: Boolean,
    val isTrackerWifiOnly: Boolean,
    val trackerFrequencyFactor: Float,
    val trackSources: Set<String>,
    val categoriesSummary: String,
    val isCategoriesEnabled: Boolean,
    val notificationsSummary: String,
    val trackerDownloadStrategy: TrackerDownloadStrategy,
    val isDozeIgnoreVisible: Boolean,
)

@Composable
fun TrackerSettingsScreen(
    trackingTitle: String,
    debugTitle: String,
    state: TrackerSettingsUiState,
    snackbarHostState: SnackbarHostState,
    frequencyOptions: List<SettingsChoiceOption<Float>>,
    trackSourcesOptions: List<SettingsChoiceOption<String>>,
    downloadStrategyOptions: List<SettingsChoiceOption<TrackerDownloadStrategy>>,
    emptyTrackSourcesText: String,
    onTrackerEnabledChange: (Boolean) -> Unit,
    onTrackerWifiOnlyChange: (Boolean) -> Unit,
    onTrackerFrequencyChange: (Float) -> Unit,
    onTrackSourcesChange: (Set<String>) -> Unit,
    onTrackCategoriesClick: () -> Unit,
    onNotificationsSettingsClick: () -> Unit,
    onTrackerDownloadStrategyChange: (TrackerDownloadStrategy) -> Unit,
    onTrackerDebugClick: () -> Unit,
    onIgnoreDozeClick: () -> Unit,
    onTrackerWarningClick: () -> Unit,
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
            item(key = "tracking") {
                SettingsPreferenceSection(title = trackingTitle) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.check_new_chapters_title),
                        checked = state.isTrackerEnabled,
                        onCheckedChange = onTrackerEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.only_using_wifi),
                        checked = state.isTrackerWifiOnly,
                        summary = stringResource(R.string.tracker_wifi_only_summary),
                        enabled = state.isTrackerEnabled,
                        onCheckedChange = onTrackerWifiOnlyChange,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.frequency_of_check),
                        value = state.trackerFrequencyFactor,
                        options = frequencyOptions,
                        enabled = state.isTrackerEnabled,
                        onValueChange = onTrackerFrequencyChange,
                    )
                    SettingsSectionDivider()
                    SettingsMultiChoicePreference(
                        title = stringResource(R.string.track_sources),
                        values = state.trackSources,
                        options = trackSourcesOptions,
                        emptySelectionText = emptyTrackSourcesText,
                        enabled = state.isTrackerEnabled,
                        onValueChange = onTrackSourcesChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.favourites_categories),
                        summary = state.categoriesSummary,
                        enabled = state.isCategoriesEnabled,
                        onClick = onTrackCategoriesClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.notifications_settings),
                        summary = state.notificationsSummary,
                        enabled = state.isTrackerEnabled,
                        onClick = onNotificationsSettingsClick,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.download_new_chapters),
                        value = state.trackerDownloadStrategy,
                        options = downloadStrategyOptions,
                        enabled = state.isTrackerEnabled,
                        onValueChange = onTrackerDownloadStrategyChange,
                    )
                }
            }
            item(key = "debug") {
                SettingsPreferenceSection(title = debugTitle) {
                    SettingsActionPreference(
                        title = stringResource(R.string.tracker_debug_info),
                        summary = stringResource(R.string.tracker_debug_info_summary),
                        enabled = state.isTrackerEnabled,
                        onClick = onTrackerDebugClick,
                    )
                    if (state.isDozeIgnoreVisible) {
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = stringResource(R.string.disable_battery_optimization),
                            summary = stringResource(R.string.disable_battery_optimization_summary),
                            enabled = state.isTrackerEnabled,
                            onClick = onIgnoreDozeClick,
                        )
                    }
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.read_more),
                        summary = stringResource(R.string.tracker_warning),
                        iconRes = R.drawable.ic_info_outline,
                        onClick = onTrackerWarningClick,
                    )
                }
            }
        }
    }
}
