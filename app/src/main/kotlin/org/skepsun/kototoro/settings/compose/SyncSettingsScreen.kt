package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings

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
)

@Composable
fun SyncSettingsScreen(
    settings: AppSettings,
    state: SyncSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onGoogleDriveSignInClick: () -> Unit,
    onGoogleDriveSignOutClick: () -> Unit,
    onGoogleDriveSyncNowClick: () -> Unit,
    onGoogleDriveDeleteRemoteClick: () -> Unit,
    onGoogleDriveImportLegacyClick: () -> Unit,
    onGoogleDriveEnabledChange: (Boolean) -> Unit,
    onGoogleDriveIntervalChange: (Int) -> Unit,
    onGoogleDriveWifiOnlyChange: (Boolean) -> Unit,
    onGoogleDriveSyncOnStartChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDeleteRemoteDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isImportLegacyDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isEnableGoogleDriveConfirmVisible by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "google_drive") {
                SettingsPreferenceGroup(title = stringResource(R.string.google_drive_sync)) {
                    if (state.isGoogleDriveSignedIn) {
                        item {
                            SettingsInfoPreference(
                                title = stringResource(R.string.sync_account),
                                iconRes = R.drawable.ic_user,
                                summary = state.googleDriveAccountSummary
                                    ?: stringResource(R.string.google_drive_sync),
                            )
                        }
                        item {
                            SettingsSwitchPreference(
                                title = stringResource(R.string.sync_google_drive_enable),
                                iconRes = R.drawable.ic_sync,
                                checked = state.isGoogleDriveEnabled,
                                summary = stringResource(R.string.sync_google_drive_enable_summary),
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
                                title = stringResource(R.string.sync_now),
                                iconRes = R.drawable.ic_cloud_upload,
                                summary = when {
                                    state.isGoogleDriveSyncing -> stringResource(R.string.sync_syncing)
                                    state.googleDriveErrorSummary != null -> state.googleDriveErrorSummary
                                    state.googleDriveLastSyncSummary != null -> stringResource(
                                        R.string.sync_last,
                                        state.googleDriveLastSyncSummary,
                                    )
                                    else -> stringResource(R.string.sync_never)
                                },
                                enabled = state.isGoogleDriveEnabled && !state.isGoogleDriveSyncing,
                                onClick = onGoogleDriveSyncNowClick,
                            )
                        }
                        item {
                            SettingsChoicePreference(
                                title = stringResource(R.string.sync_frequency),
                                iconRes = R.drawable.ic_schedule,
                                value = state.googleDriveIntervalMinutes,
                                options = listOf(
                                    SettingsChoiceOption(0, stringResource(R.string.sync_freq_off)),
                                    SettingsChoiceOption(360, stringResource(R.string.sync_freq_6h)),
                                    SettingsChoiceOption(720, stringResource(R.string.sync_freq_12h)),
                                    SettingsChoiceOption(1440, stringResource(R.string.sync_freq_daily)),
                                    SettingsChoiceOption(10080, stringResource(R.string.sync_freq_weekly)),
                                ),
                                enabled = state.isGoogleDriveEnabled,
                                onValueChange = onGoogleDriveIntervalChange,
                            )
                        }
                        item {
                            SettingsSwitchPreference(
                                title = stringResource(R.string.sync_wifi_only),
                                iconRes = R.drawable.ic_wifi,
                                checked = state.isGoogleDriveWifiOnly,
                                enabled = state.isGoogleDriveEnabled,
                                onCheckedChange = onGoogleDriveWifiOnlyChange,
                            )
                        }
                        item {
                            SettingsSwitchPreference(
                                title = stringResource(R.string.sync_on_start),
                                iconRes = R.drawable.ic_bolt,
                                checked = state.isGoogleDriveSyncOnStart,
                                summary = stringResource(R.string.sync_on_start_summary),
                                enabled = state.isGoogleDriveEnabled,
                                onCheckedChange = onGoogleDriveSyncOnStartChange,
                            )
                        }
                        item {
                            SettingsActionPreference(
                                title = stringResource(R.string.sync_delete_remote_data),
                                iconRes = R.drawable.ic_delete_all,
                                summary = stringResource(R.string.sync_delete_remote_data_summary),
                                enabled = state.isGoogleDriveEnabled && !state.isGoogleDriveSyncing,
                                onClick = { isDeleteRemoteDialogVisible = true },
                            )
                        }
                        item {
                            SettingsActionPreference(
                                title = stringResource(R.string.sync_import_legacy_remote_data),
                                iconRes = R.drawable.ic_import,
                                summary = stringResource(R.string.sync_import_legacy_remote_data_summary),
                                enabled = state.isGoogleDriveEnabled && !state.isGoogleDriveSyncing,
                                onClick = { isImportLegacyDialogVisible = true },
                            )
                        }
                        item {
                            SettingsActionPreference(
                                title = stringResource(R.string.sync_sign_out),
                                iconRes = R.drawable.ic_lock_open,
                                onClick = onGoogleDriveSignOutClick,
                            )
                        }
                    } else {
                        item {
                            SettingsActionPreference(
                                title = stringResource(R.string.sync_sign_in),
                                iconRes = R.drawable.ic_user,
                                summary = stringResource(R.string.sync_sign_in_summary),
                                onClick = onGoogleDriveSignInClick,
                            )
                        }
                    }
                }
            }
        }
    }
    if (isDeleteRemoteDialogVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isDeleteRemoteDialogVisible = false },
            title = stringResource(R.string.sync_delete_remote_data),
            text = { Text(text = stringResource(R.string.sync_delete_remote_data_confirm)) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.clear),
                    onClick = {
                        isDeleteRemoteDialogVisible = false
                        onGoogleDriveDeleteRemoteClick()
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isDeleteRemoteDialogVisible = false },
                )
            },
        )
    }
    if (isEnableGoogleDriveConfirmVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isEnableGoogleDriveConfirmVisible = false },
            title = stringResource(R.string.sync_backend_switch_google_drive_title),
            text = { Text(text = stringResource(R.string.sync_backend_switch_google_drive_confirm)) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.enable),
                    onClick = {
                        isEnableGoogleDriveConfirmVisible = false
                        onGoogleDriveEnabledChange(true)
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isEnableGoogleDriveConfirmVisible = false },
                )
            },
        )
    }
    if (isImportLegacyDialogVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isImportLegacyDialogVisible = false },
            title = stringResource(R.string.sync_import_legacy_remote_data),
            text = { Text(text = stringResource(R.string.sync_import_legacy_remote_data_confirm)) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.import_legacy_sync),
                    onClick = {
                        isImportLegacyDialogVisible = false
                        onGoogleDriveImportLegacyClick()
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isImportLegacyDialogVisible = false },
                )
            },
        )
    }
}
