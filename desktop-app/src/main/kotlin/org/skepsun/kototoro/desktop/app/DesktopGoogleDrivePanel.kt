package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.settings.compose.GoogleDriveSyncSettingsContent
import org.skepsun.kototoro.settings.compose.SyncSettingsUiState
import java.text.DateFormat
import java.util.Date

/** The host only projects persisted state and supplies desktop operations to Android's shared controls. */
@Composable
internal fun DesktopGoogleDriveSection(
    controller: DesktopController,
    drive: DesktopDriveUi,
    webDavConfigured: Boolean,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GoogleDriveSyncSettingsContent(
            state = SyncSettingsUiState(
                isGoogleDriveSignedIn = drive.signedIn,
                isGoogleDriveEnabled = drive.enabled,
                isWebDavEnabled = webDavConfigured && !drive.enabled,
                googleDriveAccountSummary = drive.account,
                googleDriveIntervalMinutes = drive.intervalMinutes,
                isGoogleDriveWifiOnly = false,
                isGoogleDriveSyncOnStart = drive.syncOnStart,
                googleDriveLastSyncSummary = drive.lastSync.takeIf { it > 0 }?.let {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, DesktopStrings.locale)
                        .format(Date(it))
                },
                googleDriveErrorSummary = drive.failure?.let { AndroidStrings[it.stringKey] },
                isGoogleDriveSyncing = drive.syncing,
                googleDriveSyncStage = drive.stage?.let { AndroidStrings["google_drive_stage_${it.name.lowercase()}"] },
                isGoogleDriveSettingsSyncEnabled = false,
                googleDriveAuthorizationRequired = drive.authorizationRequired,
            ),
            onGoogleDriveSignInClick = { controller.googleDriveSignIn() },
            onGoogleDriveSignOutClick = { controller.googleDriveSignOut() },
            onGoogleDriveSyncNowClick = { controller.googleDriveSyncNow() },
            onGoogleDriveEnabledChange = { controller.googleDriveSettings(enabled = it) },
            onGoogleDriveIntervalChange = { controller.googleDriveSettings(interval = it) },
            onGoogleDriveSyncOnStartChange = { controller.googleDriveSettings(onStart = it) },
            onGoogleDriveWifiOnlyChange = {},
            onGoogleDriveSettingsSyncEnabledChange = {},
            onGoogleDriveDeleteRemoteClick = {},
            onGoogleDriveImportLegacyClick = {},
            supportsWifiOnly = false,
            supportsSettingsSync = false,
            supportsRemoteDelete = false,
            supportsLegacyImport = false,
            enabled = enabled && !drive.syncing,
        )
        Text(AndroidStrings["google_drive_desktop_scope"])
    }
}
