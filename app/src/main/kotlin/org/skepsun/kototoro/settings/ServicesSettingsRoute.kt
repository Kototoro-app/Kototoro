package org.skepsun.kototoro.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsUiState
import org.skepsun.kototoro.settings.compose.ServicesSettingsScreen

@Composable
fun ServicesSettingsRoute(
    settings: AppSettings,
    onSuggestionsClick: () -> Unit,
    onStatsClick: () -> Unit,
    onDiscordSettingsClick: () -> Unit,
) {
    val suggestionsEnabled = settings.observeAsState(AppSettings.KEY_SUGGESTIONS) { isSuggestionsEnabled }.value
    val isBrowseTrackingRecommendationsEnabled =
        settings.observeAsState(AppSettings.KEY_BROWSE_TRACKING_RECOMMENDATIONS) { isBrowseTrackingRecommendationsEnabled }.value
    val isBrowseMoreTrackingRecommendationsEnabled =
        settings.observeAsState(AppSettings.KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS) {
            isBrowseMoreTrackingRecommendationsEnabled
        }.value
    val isRelatedContentEnabled =
        settings.observeAsState(AppSettings.KEY_RELATED_MANGA) { isRelatedContentEnabled }.value
    val isStatsEnabled = settings.observeAsState(AppSettings.KEY_STATS_ENABLED) { isStatsEnabled }.value
    val isReadingTimeEstimationEnabled =
        settings.observeAsState(AppSettings.KEY_READING_TIME) { isReadingTimeEstimationEnabled }.value
    val snackbarHostState = remember { SnackbarHostState() }

    ServicesSettingsScreen(
        state = ServicesSettingsUiState(
            suggestionsEnabled = suggestionsEnabled,
            relatedContent = isRelatedContentEnabled,
            statsEnabled = isStatsEnabled,
            readingTimeEstimation = isReadingTimeEstimationEnabled,
            browseTracking = isBrowseTrackingRecommendationsEnabled,
            browseMoreTracking = isBrowseMoreTrackingRecommendationsEnabled,
        ),
        snackbarHostState = snackbarHostState,
        onSuggestionsClick = onSuggestionsClick,
        onBrowseTrackingRecommendationsChange = { settings.isBrowseTrackingRecommendationsEnabled = it },
        onBrowseMoreTrackingRecommendationsChange = { settings.isBrowseMoreTrackingRecommendationsEnabled = it },
        onRelatedContentChange = { settings.isRelatedContentEnabled = it },
        onStatsClick = onStatsClick,
        onStatsEnabledChange = { settings.isStatsEnabled = it },
        onReadingTimeChange = { settings.isReadingTimeEstimationEnabled = it },
        onDiscordSettingsClick = onDiscordSettingsClick,
    )
}
