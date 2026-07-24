package org.skepsun.kototoro.settings.tracker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.TrackerDownloadStrategy
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.SettingsDestination
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.TrackerSettingsScreen
import org.skepsun.kototoro.settings.compose.TrackerSettingsUiState
import org.skepsun.kototoro.tracker.ui.debug.TrackerDebugActivity
import org.skepsun.kototoro.tracker.work.TrackerNotificationHelper

@Composable
fun TrackerSettingsRoute(
    settings: AppSettings,
    notificationHelper: TrackerNotificationHelper,
    viewModel: TrackerSettingsViewModel,
    dozeRefreshKey: Int,
    notificationRefreshKey: Int,
    onTrackCategoriesClick: () -> Unit,
    onOpenNotificationsSettings: (onUnsupported: () -> Unit) -> Unit,
    onOpenTrackerDebug: () -> Unit,
    onRequestIgnoreDoze: () -> Boolean,
    onOpenTrackerWarning: (onUnsupported: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val isTrackerEnabled = settings.observeAsState(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled }.value
    val isTrackerWifiOnly = settings.observeAsState(AppSettings.KEY_TRACKER_WIFI_ONLY) { isTrackerWifiOnly }.value
    val trackerFrequencyFactor =
        settings.observeAsState(AppSettings.KEY_TRACKER_FREQUENCY) { trackerFrequencyFactor }.value
    val trackSources = settings.observeAsState(AppSettings.KEY_TRACK_SOURCES) { trackSources }.value
    val trackerDownloadStrategy =
        settings.observeAsState(AppSettings.KEY_TRACKER_DOWNLOAD) { trackerDownloadStrategy }.value
    val categoriesCount = viewModel.categoriesCount.collectAsStateWithLifecycle().value
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val frequencyOptions = listOf(
        SettingsChoiceOption(-1f, context.getString(R.string.manual)),
        SettingsChoiceOption(0.4f, context.getString(R.string.less_frequently)),
        SettingsChoiceOption(1f, context.getString(R.string.system_default)),
        SettingsChoiceOption(2f, context.getString(R.string.more_frequently)),
    )
    val trackSourcesOptions = listOf(
        SettingsChoiceOption(AppSettings.TRACK_FAVOURITES, context.getString(R.string.favourites)),
        SettingsChoiceOption(AppSettings.TRACK_HISTORY, context.getString(R.string.history)),
    )
    val downloadStrategyOptions = listOf(
        SettingsChoiceOption(TrackerDownloadStrategy.DISABLED, context.getString(R.string.never)),
        SettingsChoiceOption(
            TrackerDownloadStrategy.DOWNLOADED,
            context.getString(R.string.manga_with_downloaded_chapters),
        ),
    )

    val categoriesSummary = categoriesCount?.let {
        context.getString(R.string.enabled_d_of_d, it[0], it[1])
    } ?: context.getString(R.string.loading_)
    val notificationsSummary = if (notificationRefreshKey >= 0 && notificationHelper.getAreNotificationsEnabled()) {
        context.getString(R.string.show_notification_new_chapters_on)
    } else {
        context.getString(R.string.show_notification_new_chapters_off)
    }

    val state = TrackerSettingsUiState(
        isTrackerEnabled = isTrackerEnabled,
        isTrackerWifiOnly = isTrackerWifiOnly,
        trackerFrequencyFactor = trackerFrequencyFactor,
        trackSources = trackSources,
        categoriesSummary = categoriesSummary,
        isCategoriesEnabled = isTrackerEnabled && AppSettings.TRACK_FAVOURITES in trackSources,
        notificationsSummary = notificationsSummary,
        trackerDownloadStrategy = trackerDownloadStrategy,
        isDozeIgnoreVisible = isTrackerDozeIgnoreAvailable(context, dozeRefreshKey),
    )

    TrackerSettingsScreen(
        trackingTitle = context.getString(R.string.check_for_new_chapters),
        debugTitle = context.getString(R.string.debug),
        state = state,
        snackbarHostState = snackbarHostState,
        frequencyOptions = frequencyOptions,
        trackSourcesOptions = trackSourcesOptions,
        downloadStrategyOptions = downloadStrategyOptions,
        emptyTrackSourcesText = context.getString(R.string.dont_check),
        onTrackerEnabledChange = { settings.isTrackerEnabled = it },
        onTrackerWifiOnlyChange = { settings.isTrackerWifiOnly = it },
        onTrackerFrequencyChange = { settings.trackerFrequencyFactor = it },
        onTrackSourcesChange = { settings.trackSources = it },
        onTrackCategoriesClick = onTrackCategoriesClick,
        onNotificationsSettingsClick = {
            onOpenNotificationsSettings {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.operation_not_supported))
                }
            }
        },
        onTrackerDownloadStrategyChange = { settings.trackerDownloadStrategy = it },
        onTrackerDebugClick = onOpenTrackerDebug,
        onIgnoreDozeClick = {
            if (!onRequestIgnoreDoze()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.operation_not_supported))
                }
            }
        },
        onTrackerWarningClick = {
            onOpenTrackerWarning {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.operation_not_supported))
                }
            }
        },
    )
}

private fun isTrackerDozeIgnoreAvailable(
    context: android.content.Context,
    refreshKey: Int,
): Boolean {
    refreshKey
    val powerManager = context.powerManager ?: return false
    return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
