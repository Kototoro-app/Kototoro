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
import org.skepsun.kototoro.backups.ui.periodical.PeriodicalBackupSettingsViewModel
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.settings.compose.SyncSettingsScreen
import org.skepsun.kototoro.settings.compose.SyncSettingsUiState
import org.skepsun.kototoro.sync.google.ui.GoogleDriveSyncSettingsViewModel

@Composable
fun SyncSettingsRoute(
    settings: AppSettings,
    backupSettingsViewModel: PeriodicalBackupSettingsViewModel,
    googleDriveSyncViewModel: GoogleDriveSyncSettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val googleDriveState = googleDriveSyncViewModel.uiState.collectAsStateWithLifecycle().value
    val webDavLastAction = backupSettingsViewModel.webDavLastAction.collectAsStateWithLifecycle().value
    val isWebDavCheckLoading = backupSettingsViewModel.isWebDavCheckLoading.collectAsStateWithLifecycle().value
    val webDavUploadBusyMessageRes = backupSettingsViewModel.webDavUploadBusyMessageRes.collectAsStateWithLifecycle().value
    val webDavRestoreBusyMessageRes = backupSettingsViewModel.webDavRestoreBusyMessageRes.collectAsStateWithLifecycle().value
    val isWebDavEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_ENABLED) { isBackupWebDavUploadEnabled }.value
    val webDavServerUrl =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_URL) { backupWebDavServerUrl.orEmpty() }.value
    val webDavUsername =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_USERNAME) { backupWebDavUsername.orEmpty() }.value
    val webDavPassword =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_PASSWORD) { backupWebDavPassword.orEmpty() }.value
    val webDavRemotePath =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_PATH) { backupWebDavRemotePath.orEmpty() }.value
    val isWebDavAutoRestoreEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_AUTO_RESTORE) { isBackupWebDavAutoRestoreEnabled }.value
    val isWebDavKeepLocalCopyEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY) { isBackupWebDavKeepLocalCopyEnabled }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val googleDriveAuthorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            googleDriveSyncViewModel.onAuthorizationResult(result.data)
        }
    }

    LaunchedEffect(backupSettingsViewModel.onError, context, snackbarHostState) {
        backupSettingsViewModel.onError.collect { event ->
            event?.consume { error ->
                snackbarHostState.showSnackbar(error.getDisplayMessage(context.resources))
            }
        }
    }
    LaunchedEffect(backupSettingsViewModel.onActionDone, context, snackbarHostState) {
        backupSettingsViewModel.onActionDone.collect { event ->
            event?.consume { action ->
                snackbarHostState.showSnackbar(context.getString(action.stringResId))
            }
        }
    }
    LaunchedEffect(googleDriveSyncViewModel.authorizationRequests, googleDriveAuthorizationLauncher) {
        googleDriveSyncViewModel.authorizationRequests.collect { pendingIntent ->
            googleDriveAuthorizationLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
            )
        }
    }

    val webDavLastActionSummary = webDavLastAction?.let {
        context.getString(it.first) + " - " + DateUtils.getRelativeTimeSpanString(it.second)
    }
    val isAnyBusy = webDavUploadBusyMessageRes != null || webDavRestoreBusyMessageRes != null
    SyncSettingsScreen(
        settings = settings,
        state = SyncSettingsUiState(
            isWebDavEnabled = isWebDavEnabled,
            webDavServerUrl = webDavServerUrl,
            webDavUsername = webDavUsername,
            webDavPassword = webDavPassword,
            webDavRemotePath = webDavRemotePath,
            isWebDavCheckLoading = isWebDavCheckLoading,
            isWebDavAutoRestoreEnabled = isWebDavAutoRestoreEnabled,
            isWebDavKeepLocalCopyEnabled = isWebDavKeepLocalCopyEnabled,
            webDavLastActionSummary = webDavLastActionSummary,
            isPolicyNoteVisible = !isWebDavKeepLocalCopyEnabled && isWebDavEnabled,
            legacyRestoreBlockSummary = null,
            webDavUploadBusySummary = webDavUploadBusyMessageRes?.let(context::getString),
            webDavRestoreBusySummary = webDavRestoreBusyMessageRes?.let(context::getString),
            isAnyBusy = isAnyBusy,
            isGoogleDriveSignedIn = googleDriveState.isSignedIn,
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
        onWebDavEnabledChange = { settings.isBackupWebDavUploadEnabled = it },
        onWebDavServerUrlChange = { settings.backupWebDavServerUrl = it },
        onWebDavUsernameChange = { settings.backupWebDavUsername = it },
        onWebDavPasswordChange = { settings.backupWebDavPassword = it },
        onWebDavRemotePathChange = { settings.backupWebDavRemotePath = it },
        onWebDavTestClick = { backupSettingsViewModel.checkWebDav() },
        onWebDavUploadNowClick = { backupSettingsViewModel.uploadWebDavNow() },
        onWebDavRestoreNowClick = { backupSettingsViewModel.restoreWebDavNow() },
        onWebDavAutoRestoreChange = { settings.isBackupWebDavAutoRestoreEnabled = it },
        onWebDavKeepLocalCopyChange = { settings.isBackupWebDavKeepLocalCopyEnabled = it },
        onGoogleDriveSignInClick = { googleDriveSyncViewModel.requestSignIn() },
        onGoogleDriveSignOutClick = { googleDriveSyncViewModel.signOut() },
        onGoogleDriveSyncNowClick = { googleDriveSyncViewModel.syncNow() },
        onGoogleDriveIntervalChange = { googleDriveSyncViewModel.setIntervalMinutes(it) },
        onGoogleDriveWifiOnlyChange = { googleDriveSyncViewModel.setWifiOnly(it) },
        onGoogleDriveSyncOnStartChange = { googleDriveSyncViewModel.setSyncOnStart(it) },
        modifier = modifier,
    )
}
