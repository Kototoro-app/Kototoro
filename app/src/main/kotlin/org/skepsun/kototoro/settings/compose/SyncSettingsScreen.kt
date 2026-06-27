package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onGoogleDriveIntervalChange: (Int) -> Unit,
    onGoogleDriveWifiOnlyChange: (Boolean) -> Unit,
    onGoogleDriveSyncOnStartChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDeleteRemoteDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isImportLegacyDialogVisible by rememberSaveable { mutableStateOf(false) }
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
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "google_drive") {
                SettingsPreferenceSection(title = stringResource(R.string.google_drive_sync)) {
                    if (state.isGoogleDriveSignedIn) {
                        SettingsInfoPreference(
                            title = stringResource(R.string.sync_account),
                            summary = state.googleDriveAccountSummary ?: stringResource(R.string.google_drive_sync),
                        )
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = stringResource(R.string.sync_now),
                            summary = when {
                                state.isGoogleDriveSyncing -> stringResource(R.string.sync_syncing)
                                state.googleDriveErrorSummary != null -> state.googleDriveErrorSummary
                                state.googleDriveLastSyncSummary != null -> stringResource(
                                    R.string.sync_last,
                                    state.googleDriveLastSyncSummary,
                                )
                                else -> stringResource(R.string.sync_never)
                            },
                            enabled = !state.isGoogleDriveSyncing,
                            onClick = onGoogleDriveSyncNowClick,
                        )
                        SettingsSectionDivider()
                        SettingsChoicePreference(
                            title = stringResource(R.string.sync_frequency),
                            value = state.googleDriveIntervalMinutes,
                            options = listOf(
                                SettingsChoiceOption(0, stringResource(R.string.sync_freq_off)),
                                SettingsChoiceOption(360, stringResource(R.string.sync_freq_6h)),
                                SettingsChoiceOption(720, stringResource(R.string.sync_freq_12h)),
                                SettingsChoiceOption(1440, stringResource(R.string.sync_freq_daily)),
                                SettingsChoiceOption(10080, stringResource(R.string.sync_freq_weekly)),
                            ),
                            onValueChange = onGoogleDriveIntervalChange,
                        )
                        SettingsSectionDivider()
                        SettingsSwitchPreference(
                            title = stringResource(R.string.sync_wifi_only),
                            checked = state.isGoogleDriveWifiOnly,
                            onCheckedChange = onGoogleDriveWifiOnlyChange,
                        )
                        SettingsSectionDivider()
                        SettingsSwitchPreference(
                            title = stringResource(R.string.sync_on_start),
                            checked = state.isGoogleDriveSyncOnStart,
                            summary = stringResource(R.string.sync_on_start_summary),
                            onCheckedChange = onGoogleDriveSyncOnStartChange,
                        )
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = stringResource(R.string.sync_delete_remote_data),
                            summary = stringResource(R.string.sync_delete_remote_data_summary),
                            enabled = !state.isGoogleDriveSyncing,
                            onClick = { isDeleteRemoteDialogVisible = true },
                        )
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = stringResource(R.string.sync_import_legacy_remote_data),
                            summary = stringResource(R.string.sync_import_legacy_remote_data_summary),
                            enabled = !state.isGoogleDriveSyncing,
                            onClick = { isImportLegacyDialogVisible = true },
                        )
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = stringResource(R.string.sync_sign_out),
                            onClick = onGoogleDriveSignOutClick,
                        )
                    } else {
                        SettingsActionPreference(
                            title = stringResource(R.string.sync_sign_in),
                            summary = stringResource(R.string.sync_sign_in_summary),
                            onClick = onGoogleDriveSignInClick,
                        )
                    }
                }
            }
        }
    }
    if (isDeleteRemoteDialogVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteRemoteDialogVisible = false },
            title = { Text(text = stringResource(R.string.sync_delete_remote_data)) },
            text = { Text(text = stringResource(R.string.sync_delete_remote_data_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleteRemoteDialogVisible = false
                        onGoogleDriveDeleteRemoteClick()
                    },
                ) {
                    Text(text = stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { isDeleteRemoteDialogVisible = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (isImportLegacyDialogVisible) {
        AlertDialog(
            onDismissRequest = { isImportLegacyDialogVisible = false },
            title = { Text(text = stringResource(R.string.sync_import_legacy_remote_data)) },
            text = { Text(text = stringResource(R.string.sync_import_legacy_remote_data_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isImportLegacyDialogVisible = false
                        onGoogleDriveImportLegacyClick()
                    },
                ) {
                    Text(text = stringResource(R.string.import_legacy_sync))
                }
            },
            dismissButton = {
                TextButton(onClick = { isImportLegacyDialogVisible = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
