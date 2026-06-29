package org.skepsun.kototoro.settings

import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.settings.compose.SyncSettingsScreen
import org.skepsun.kototoro.settings.compose.SyncSettingsUiState
import org.skepsun.kototoro.sync.google.ui.GoogleDriveSyncSettingsViewModel

@Composable
fun SyncSettingsRoute(
    settings: AppSettings,
    googleDriveSyncViewModel: GoogleDriveSyncSettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val googleDriveState = googleDriveSyncViewModel.uiState.collectAsStateWithLifecycle().value
    val isWebDavEnabled = settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_ENABLED) {
        isBackupWebDavUploadEnabled
    }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val googleDriveAuthorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            googleDriveSyncViewModel.onAuthorizationResult(result.data)
        }
    }

    LaunchedEffect(googleDriveSyncViewModel.authorizationRequests, googleDriveAuthorizationLauncher) {
        googleDriveSyncViewModel.authorizationRequests.collect { pendingIntent ->
            googleDriveAuthorizationLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
            )
        }
    }

    SyncSettingsScreen(
        settings = settings,
        state = SyncSettingsUiState(
            isGoogleDriveSignedIn = googleDriveState.isSignedIn,
            isGoogleDriveEnabled = googleDriveState.isEnabled,
            isWebDavEnabled = isWebDavEnabled,
            googleDriveAccountSummary = googleDriveState.accountName ?: googleDriveState.accountEmail,
            googleDriveIntervalMinutes = googleDriveState.intervalMinutes,
            isGoogleDriveWifiOnly = googleDriveState.isWifiOnly,
            isGoogleDriveSyncOnStart = googleDriveState.isSyncOnStart,
            googleDriveLastSyncSummary = googleDriveState.lastSyncTimestamp.takeIf { it > 0L }?.let {
                DateUtils.getRelativeTimeSpanString(it).toString()
            },
            googleDriveErrorSummary = googleDriveState.lastError,
            isGoogleDriveSyncing = googleDriveState.isSyncing,
        ),
        snackbarHostState = snackbarHostState,
        onGoogleDriveSignInClick = { googleDriveSyncViewModel.requestSignIn() },
        onGoogleDriveSignOutClick = { googleDriveSyncViewModel.signOut() },
        onGoogleDriveSyncNowClick = { googleDriveSyncViewModel.syncNow() },
        onGoogleDriveDeleteRemoteClick = { googleDriveSyncViewModel.deleteRemoteData() },
        onGoogleDriveImportLegacyClick = { googleDriveSyncViewModel.importLegacyRemoteData() },
        onGoogleDriveEnabledChange = { googleDriveSyncViewModel.setEnabled(it) },
        onGoogleDriveIntervalChange = { googleDriveSyncViewModel.setIntervalMinutes(it) },
        onGoogleDriveWifiOnlyChange = { googleDriveSyncViewModel.setWifiOnly(it) },
        onGoogleDriveSyncOnStartChange = { googleDriveSyncViewModel.setSyncOnStart(it) },
        modifier = modifier,
    )
}
