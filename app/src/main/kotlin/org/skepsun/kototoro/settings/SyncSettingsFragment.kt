package org.skepsun.kototoro.settings

import android.text.format.DateUtils
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.backups.ui.periodical.PeriodicalBackupSettingsViewModel
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.settings.compose.SyncSettingsScreen
import org.skepsun.kototoro.settings.compose.SyncSettingsUiState

@Composable
fun SyncSettingsRoute(
    settings: AppSettings,
    backupSettingsViewModel: PeriodicalBackupSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webDavLastAction = backupSettingsViewModel.webDavLastAction.collectAsStateWithLifecycle().value
    val isWebDavCheckLoading = backupSettingsViewModel.isWebDavCheckLoading.collectAsStateWithLifecycle().value
    val webDavBusyMessageRes = backupSettingsViewModel.webDavBusyMessageRes.collectAsStateWithLifecycle().value
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
    val isWebDavAutoSyncEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_AUTO_SYNC) { isBackupWebDavAutoSyncEnabled }.value
    val isWebDavAutoRestoreEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_AUTO_RESTORE) { isBackupWebDavAutoRestoreEnabled }.value
    val isWebDavKeepLocalCopyEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY) { isBackupWebDavKeepLocalCopyEnabled }.value
    val isLegacyRestoreUploadBlocked =
        settings.observeAsState(
            AppSettings.KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE,
        ) { isBackupWebDavAutoUploadBlockedByLegacyRestore }.value
    val snackbarHostState = remember { SnackbarHostState() }

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

    val webDavLastActionSummary = webDavLastAction?.let {
        context.getString(it.first) + " - " + DateUtils.getRelativeTimeSpanString(it.second)
    }
    SyncSettingsScreen(
        settings = settings,
        state = SyncSettingsUiState(
            isWebDavEnabled = isWebDavEnabled,
            webDavServerUrl = webDavServerUrl,
            webDavUsername = webDavUsername,
            webDavPassword = webDavPassword,
            webDavRemotePath = webDavRemotePath,
            isWebDavCheckLoading = isWebDavCheckLoading,
            isWebDavAutoSyncEnabled = isWebDavAutoSyncEnabled,
            isWebDavAutoRestoreEnabled = isWebDavAutoRestoreEnabled,
            isWebDavKeepLocalCopyEnabled = isWebDavKeepLocalCopyEnabled,
            webDavLastActionSummary = webDavLastActionSummary,
            isPolicyNoteVisible = !isWebDavKeepLocalCopyEnabled && isWebDavEnabled,
            legacyRestoreBlockSummary = if (isLegacyRestoreUploadBlocked) {
                context.getString(R.string.webdav_legacy_restore_block_summary)
            } else {
                null
            },
            webDavBusySummary = webDavBusyMessageRes?.let(context::getString),
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
        onWebDavAutoSyncChange = { settings.isBackupWebDavAutoSyncEnabled = it },
        onWebDavAutoRestoreChange = { settings.isBackupWebDavAutoRestoreEnabled = it },
        onWebDavKeepLocalCopyChange = { settings.isBackupWebDavKeepLocalCopyEnabled = it },
        modifier = modifier,
    )
}
