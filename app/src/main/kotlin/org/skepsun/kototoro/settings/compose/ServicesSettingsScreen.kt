package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

data class ServicesSettingsUiState(
    val suggestionsSummary: String,
    val isBrowseTrackingRecommendationsEnabled: Boolean,
    val isBrowseMoreTrackingRecommendationsEnabled: Boolean,
    val isRelatedContentEnabled: Boolean,
    val isStatsEnabled: Boolean,
    val isReadingTimeEstimationEnabled: Boolean,
)

@Composable
fun ServicesSettingsScreen(
    servicesTitle: String,
    state: ServicesSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onSuggestionsClick: () -> Unit,
    onBrowseTrackingRecommendationsChange: (Boolean) -> Unit,
    onBrowseMoreTrackingRecommendationsChange: (Boolean) -> Unit,
    onRelatedContentChange: (Boolean) -> Unit,
    onStatsClick: () -> Unit,
    onStatsEnabledChange: (Boolean) -> Unit,
    onReadingTimeChange: (Boolean) -> Unit,
    onDiscordSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
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
            item(key = "services") {
                SettingsPreferenceSection(title = servicesTitle) {
                    SettingsActionPreference(
                        title = stringResource(R.string.suggestions),
                        summary = state.suggestionsSummary,
                        onClick = onSuggestionsClick,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.browse_tracking_recommendations),
                        checked = state.isBrowseTrackingRecommendationsEnabled,
                        summary = stringResource(R.string.browse_tracking_recommendations_summary),
                        onCheckedChange = onBrowseTrackingRecommendationsChange,
                    )
                    if (state.isBrowseTrackingRecommendationsEnabled) {
                        SettingsSectionDivider()
                        SettingsSwitchPreference(
                            title = stringResource(R.string.browse_more_tracking_recommendations),
                            checked = state.isBrowseMoreTrackingRecommendationsEnabled,
                            summary = stringResource(R.string.browse_more_tracking_recommendations_summary),
                            onCheckedChange = onBrowseMoreTrackingRecommendationsChange,
                        )
                    }
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.related_manga),
                        checked = state.isRelatedContentEnabled,
                        summary = stringResource(R.string.related_manga_summary),
                        onCheckedChange = onRelatedContentChange,
                    )
                    SettingsSectionDivider()
                    SettingsSplitSwitchPreference(
                        title = stringResource(R.string.reading_stats),
                        checked = state.isStatsEnabled,
                        summary = if (state.isStatsEnabled) {
                            stringResource(R.string.enabled)
                        } else {
                            stringResource(R.string.disabled)
                        },
                        onClick = onStatsClick,
                        onCheckedChange = onStatsEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.reading_time_estimation),
                        checked = state.isReadingTimeEstimationEnabled,
                        summary = stringResource(R.string.reading_time_estimation_summary),
                        onCheckedChange = onReadingTimeChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.discord_rpc),
                        summary = stringResource(R.string.discord_rpc_summary),
                        iconRes = R.drawable.ic_discord,
                        onClick = onDiscordSettingsClick,
                    )
                }
            }
        }
    }
}
