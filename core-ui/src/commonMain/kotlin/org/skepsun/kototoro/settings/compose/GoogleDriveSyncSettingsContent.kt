package org.skepsun.kototoro.settings.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

data class SyncSettingsUiState(
    val isGoogleDriveSignedIn: Boolean,
    val isGoogleDriveEnabled: Boolean,
    val isWebDavEnabled: Boolean,
    val googleDriveAccountSummary: String?,
    val googleDriveIntervalMinutes: Int,
    val isGoogleDriveWifiOnly: Boolean,
    val isGoogleDriveSyncOnStart: Boolean,
    val googleDriveLastSyncSummary: String?,
    val googleDriveErrorSummary: String?,
    val isGoogleDriveSyncing: Boolean,
    val googleDriveSyncStage: String? = null,
    val isGoogleDriveSettingsSyncEnabled: Boolean = true,
    val googleDriveAuthorizationRequired: Boolean = false,
)

/** The same account, sync controls and confirmations on Android and desktop; hosts declare supported operations. */
@Composable
fun GoogleDriveSyncSettingsContent(
    state: SyncSettingsUiState,
    onGoogleDriveSignInClick: () -> Unit,
    onGoogleDriveSignOutClick: () -> Unit,
    onGoogleDriveSyncNowClick: () -> Unit,
    onGoogleDriveDeleteRemoteClick: () -> Unit,
    onGoogleDriveImportLegacyClick: () -> Unit,
    onGoogleDriveEnabledChange: (Boolean) -> Unit,
    onGoogleDriveIntervalChange: (Int) -> Unit,
    onGoogleDriveWifiOnlyChange: (Boolean) -> Unit,
    onGoogleDriveSyncOnStartChange: (Boolean) -> Unit,
    onGoogleDriveSettingsSyncEnabledChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supportsWifiOnly: Boolean = true,
    supportsSettingsSync: Boolean = true,
    supportsRemoteDelete: Boolean = true,
    supportsLegacyImport: Boolean = true,
) {
    var isDeleteRemoteDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isImportLegacyDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isEnableGoogleDriveConfirmVisible by rememberSaveable { mutableStateOf(false) }
    SettingsPreferenceGroup(title = uiString("google_drive_sync")) {
        if (state.isGoogleDriveSignedIn || (state.isGoogleDriveEnabled && state.googleDriveAuthorizationRequired)) {
            if (state.googleDriveAuthorizationRequired) item {
                SettingsActionPreference(
                    title = uiString("sync_sign_in"),
                    summary = uiString("google_drive_authorization_required"),
                    enabled = enabled && !state.isGoogleDriveSyncing,
                    modifier = Modifier.testTag("drive-sign-in"),
                    onClick = onGoogleDriveSignInClick,
                )
            }
            if (state.isGoogleDriveSignedIn) item {
                SettingsInfoPreference(
                    title = uiString("sync_account"),
                    icon = rememberVectorPainter(ReaderPanelIcons.User),
                    summary = state.googleDriveAccountSummary
                        ?: uiString("google_drive_sync"),
                )
            }
            item {
                SettingsSwitchPreference(
                    title = uiString("sync_google_drive_enable"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Sync),
                    checked = state.isGoogleDriveEnabled,
                    enabled = enabled,
                    summary = uiString("sync_google_drive_enable_summary"),
                    onCheckedChange = { enabled ->
                        if (enabled && state.isWebDavEnabled) {
                            isEnableGoogleDriveConfirmVisible = true
                        } else {
                            onGoogleDriveEnabledChange(enabled)
                        }
                    },
                )
            }
            item {
                SettingsActionPreference(
                    title = uiString("sync_now"),
                    icon = rememberVectorPainter(ReaderPanelIcons.CloudUpload),
                    summary = when {
                        state.isGoogleDriveSyncing -> uiString("sync_syncing") +
                            (state.googleDriveSyncStage?.let { " – $it" } ?: "")
                        state.googleDriveErrorSummary != null -> state.googleDriveErrorSummary
                        state.googleDriveLastSyncSummary != null -> uiString(
                            "sync_last",
                            state.googleDriveLastSyncSummary,
                        )
                        else -> uiString("sync_never")
                    },
                    enabled = enabled && state.isGoogleDriveEnabled && !state.isGoogleDriveSyncing &&
                        !state.googleDriveAuthorizationRequired,
                    modifier = Modifier.testTag("drive-sync-now"),
                    onClick = onGoogleDriveSyncNowClick,
                )
            }
            item {
                SettingsChoicePreference(
                    title = uiString("sync_frequency"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Schedule),
                    value = state.googleDriveIntervalMinutes,
                    options = listOf(
                        SettingsChoiceOption(0, uiString("sync_freq_off")),
                        SettingsChoiceOption(360, uiString("sync_freq_6h")),
                        SettingsChoiceOption(720, uiString("sync_freq_12h")),
                        SettingsChoiceOption(1440, uiString("sync_freq_daily")),
                        SettingsChoiceOption(10080, uiString("sync_freq_weekly")),
                    ),
                    enabled = enabled && state.isGoogleDriveEnabled,
                    onValueChange = onGoogleDriveIntervalChange,
                )
            }
            if (supportsWifiOnly) item {
                SettingsSwitchPreference(
                    title = uiString("sync_wifi_only"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Wifi),
                    checked = state.isGoogleDriveWifiOnly,
                    enabled = enabled && state.isGoogleDriveEnabled,
                    onCheckedChange = onGoogleDriveWifiOnlyChange,
                )
            }
            item {
                SettingsSwitchPreference(
                    title = uiString("sync_on_start"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Bolt),
                    checked = state.isGoogleDriveSyncOnStart,
                    summary = uiString("sync_on_start_summary"),
                    enabled = enabled && state.isGoogleDriveEnabled,
                    onCheckedChange = onGoogleDriveSyncOnStartChange,
                )
            }
            if (supportsSettingsSync) item {
                SettingsSwitchPreference(
                    title = uiString("sync_settings_enabled_title"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Settings),
                    checked = state.isGoogleDriveSettingsSyncEnabled,
                    summary = uiString("sync_settings_enabled_summary"),
                    enabled = enabled && state.isGoogleDriveEnabled,
                    onCheckedChange = onGoogleDriveSettingsSyncEnabledChange,
                )
            }
            if (supportsRemoteDelete) item {
                SettingsActionPreference(
                    title = uiString("sync_delete_remote_data"),
                    icon = rememberVectorPainter(ReaderPanelIcons.DeleteAll),
                    summary = uiString("sync_delete_remote_data_summary"),
                    enabled = enabled && state.isGoogleDriveEnabled && !state.isGoogleDriveSyncing,
                    onClick = { isDeleteRemoteDialogVisible = true },
                )
            }
            if (supportsLegacyImport) item {
                SettingsActionPreference(
                    title = uiString("sync_import_legacy_remote_data"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Import),
                    summary = uiString("sync_import_legacy_remote_data_summary"),
                    enabled = enabled && state.isGoogleDriveEnabled && !state.isGoogleDriveSyncing,
                    onClick = { isImportLegacyDialogVisible = true },
                )
            }
            if (state.isGoogleDriveSignedIn) item {
                SettingsActionPreference(
                    title = uiString("sync_sign_out"),
                    icon = rememberVectorPainter(ReaderPanelIcons.LockOpen),
                    enabled = enabled && !state.isGoogleDriveSyncing,
                    modifier = Modifier.testTag("drive-sign-out"),
                    onClick = onGoogleDriveSignOutClick,
                )
            }
        } else {
            item {
                SettingsActionPreference(
                    title = uiString("sync_sign_in"),
                    icon = rememberVectorPainter(ReaderPanelIcons.User),
                    summary = if (state.isGoogleDriveSyncing) uiString("google_drive_authorization_pending")
                        else state.googleDriveErrorSummary?.let {
                            "${uiString("sync_sign_in_summary")}\n\n⚠️ $it"
                        } ?: uiString("sync_sign_in_summary"),
                    enabled = enabled && !state.isGoogleDriveSyncing,
                    modifier = Modifier.testTag("drive-sign-in"),
                    onClick = onGoogleDriveSignInClick,
                )
            }
        }
    }
    if (isDeleteRemoteDialogVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isDeleteRemoteDialogVisible = false },
            title = uiString("sync_delete_remote_data"),
            text = { Text(text = uiString("sync_delete_remote_data_confirm")) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = uiString("clear"),
                    onClick = {
                        isDeleteRemoteDialogVisible = false
                        onGoogleDriveDeleteRemoteClick()
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = uiString("cancel"),
                    onClick = { isDeleteRemoteDialogVisible = false },
                )
            },
        )
    }
    if (isEnableGoogleDriveConfirmVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isEnableGoogleDriveConfirmVisible = false },
            title = uiString("sync_backend_switch_google_drive_title"),
            text = { Text(text = uiString("sync_backend_switch_google_drive_confirm")) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = uiString("enable"),
                    onClick = {
                        isEnableGoogleDriveConfirmVisible = false
                        onGoogleDriveEnabledChange(true)
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = uiString("cancel"),
                    onClick = { isEnableGoogleDriveConfirmVisible = false },
                )
            },
        )
    }
    if (isImportLegacyDialogVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isImportLegacyDialogVisible = false },
            title = uiString("sync_import_legacy_remote_data"),
            text = { Text(text = uiString("sync_import_legacy_remote_data_confirm")) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = uiString("import_legacy_sync"),
                    onClick = {
                        isImportLegacyDialogVisible = false
                        onGoogleDriveImportLegacyClick()
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = uiString("cancel"),
                    onClick = { isImportLegacyDialogVisible = false },
                )
            },
        )
    }
}
