package org.skepsun.kototoro.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.ui.CommunityDialogActions
import org.skepsun.kototoro.community.ui.CommunitySettingsDialogs
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.skepsun.kototoro.community.ui.CommunitySettingsViewModel
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.settings.CommunityServicesState
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
    val isCommunityEnabled = settings.observeAsState(AppSettings.KEY_COMMUNITY_ENABLED) { isCommunityEnabled }.value
    val isCommunityNsfwEnabled =
        settings.observeAsState(AppSettings.KEY_COMMUNITY_NSFW) { isCommunityNsfwRatingEnabled }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val isCommunityShareSources =
        settings.observeAsState(AppSettings.KEY_COMMUNITY_SHARE_SOURCES) { isCommunityShareSourcesEnabled }.value
    val communityViewModel = hiltViewModel<CommunitySettingsViewModel>()
    val communityDialog = communityViewModel.dialog.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ServicesSettingsScreen(
        state = ServicesSettingsUiState(
            suggestionsEnabled = suggestionsEnabled,
            relatedContent = isRelatedContentEnabled,
            statsEnabled = isStatsEnabled,
            readingTimeEstimation = isReadingTimeEstimationEnabled,
            browseTracking = isBrowseTrackingRecommendationsEnabled,
            browseMoreTracking = isBrowseMoreTrackingRecommendationsEnabled,
            community = CommunityServicesState(isCommunityEnabled, isCommunityNsfwEnabled, isCommunityShareSources),
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
        onCommunityChange = communityViewModel::setEnabled,
        onCommunityNsfwChange = { settings.isCommunityNsfwRatingEnabled = it },
        onCommunityShareSourcesChange = { settings.isCommunityShareSourcesEnabled = it },
        onCommunityRecoveryKeyClick = communityViewModel::showRecoveryKey,
        onCommunityNicknameClick = communityViewModel::showNickname,
    )
    val clipboard = LocalClipboardManager.current
    CommunitySettingsDialogs(
        dialog = communityDialog,
        actions = remember(communityViewModel, clipboard) {
            object : CommunityDialogActions {
                override fun dismiss() = communityViewModel.dismiss()
                override fun createIdentity() = communityViewModel.createIdentity()
                override fun startRestore() = communityViewModel.startRestore()
                override fun restore(recoveryKey: String) = communityViewModel.restore(recoveryKey)
                override fun saveNickname(nickname: String) = communityViewModel.saveNickname(nickname)
                override fun copyRecoveryKey(key: String) {
                    clipboard.setText(AnnotatedString(key))
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.community_recovery_key_copied)) }
                }
            }
        },
    )
}
