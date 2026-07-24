package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import javax.inject.Inject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.ServicesSettingsScreen
import org.skepsun.kototoro.settings.compose.ServicesSettingsUiState

@Composable
fun ServicesSettingsRoute(
    settings: AppSettings,
    onSuggestionsClick: () -> Unit,
    onStatsClick: () -> Unit,
    onDiscordSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
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

    val state = ServicesSettingsUiState(
        suggestionsSummary = if (suggestionsEnabled) {
            context.getString(R.string.enabled)
        } else {
            context.getString(R.string.disabled)
        },
        isBrowseTrackingRecommendationsEnabled = isBrowseTrackingRecommendationsEnabled,
        isBrowseMoreTrackingRecommendationsEnabled = isBrowseMoreTrackingRecommendationsEnabled,
        isRelatedContentEnabled = isRelatedContentEnabled,
        isStatsEnabled = isStatsEnabled,
        isReadingTimeEstimationEnabled = isReadingTimeEstimationEnabled,
    )

    ServicesSettingsScreen(
        servicesTitle = context.getString(R.string.services),
        state = state,
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
