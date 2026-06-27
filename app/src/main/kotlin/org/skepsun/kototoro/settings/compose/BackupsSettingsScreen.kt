package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.external.ExternalBackupApp
import org.skepsun.kototoro.backups.ui.periodical.BackupFileInfo
import org.skepsun.kototoro.backups.ui.periodical.WebDavRemoteBackupRestoreStatus

data class BackupsSettingsUiState(
    val backupOutputSummary: String,
    val isBackupOutputInvalid: Boolean,
    val backupFrequency: Float,
    val isPeriodicalTrimEnabled: Boolean,
    val periodicalBackupCount: Int,
    val lastBackupSummary: String?,
    val isExternalImportDialogVisible: Boolean,
    val isWebDavEnabled: Boolean,
    val webDavServerUrl: String,
    val webDavUsername: String,
    val webDavPassword: String,
    val webDavRemotePath: String,
    val isWebDavCheckLoading: Boolean,
    val isWebDavAutoRestoreEnabled: Boolean,
    val isWebDavKeepLocalCopyEnabled: Boolean,
    val webDavLastActionSummary: String?,
    val isWebDavPolicyNoteVisible: Boolean,
    val webDavUploadBusySummary: String?,
    val webDavRestoreBusySummary: String?,
    val webDavRemoteBackupBusySummary: String?,
    val webDavRemoteBackups: List<WebDavRemoteBackupUiItem>,
    val isWebDavBusy: Boolean,
)

data class WebDavRemoteBackupUiItem(
    val file: BackupFileInfo,
    val title: String,
    val summary: String,
    val restoreStatus: WebDavRemoteBackupRestoreStatus,
)

@Composable
fun BackupsSettingsScreen(
    backupRestoreTitle: String,
    state: BackupsSettingsUiState,
    snackbarHostState: SnackbarHostState,
    backupFrequencyOptions: List<SettingsChoiceOption<Float>>,
    onBackupOutputClick: () -> Unit,
    onBackupFrequencyChange: (Float) -> Unit,
    onPeriodicalTrimChange: (Boolean) -> Unit,
    onPeriodicalBackupCountChange: (Int) -> Unit,
    onCreateBackupClick: () -> Unit,
    onRestoreBackupClick: () -> Unit,
    onExportMihonBackupClick: () -> Unit,
    onExportAniyomiBackupClick: () -> Unit,
    onImportExternalBackupClick: () -> Unit,
    onDismissExternalImportDialog: () -> Unit,
    onImportExternalBackupAppClick: (ExternalBackupApp) -> Unit,
    onWebDavEnabledChange: (Boolean) -> Unit,
    onWebDavServerUrlChange: (String) -> Unit,
    onWebDavUsernameChange: (String) -> Unit,
    onWebDavPasswordChange: (String) -> Unit,
    onWebDavRemotePathChange: (String) -> Unit,
    onWebDavTestClick: () -> Unit,
    onWebDavUploadNowClick: () -> Unit,
    onWebDavRestoreNowClick: () -> Unit,
    onWebDavRefreshRemoteBackupsClick: () -> Unit,
    onWebDavInspectRemoteBackupsClick: () -> Unit,
    onWebDavRestoreRemoteBackupClick: (BackupFileInfo) -> Unit,
    onWebDavDeleteRemoteBackupClick: (BackupFileInfo) -> Unit,
    onWebDavClearRemoteBackupsClick: () -> Unit,
    onWebDavAutoRestoreChange: (Boolean) -> Unit,
    onWebDavKeepLocalCopyChange: (Boolean) -> Unit,
) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        var selectedRemoteBackup by remember { mutableStateOf<WebDavRemoteBackupUiItem?>(null) }
        var isClearRemoteBackupsConfirmVisible by rememberSaveable { mutableStateOf(false) }
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
            item(key = "backup_restore") {
                SettingsPreferenceSection(title = backupRestoreTitle) {
                    SettingsActionPreference(
                        title = stringResource(R.string.backups_output_directory),
                        summary = state.backupOutputSummary,
                        iconRes = if (state.isBackupOutputInvalid) R.drawable.ic_info_outline else null,
                        onClick = onBackupOutputClick,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.backup_frequency),
                        value = state.backupFrequency,
                        options = backupFrequencyOptions,
                        onValueChange = onBackupFrequencyChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.delete_old_backups),
                        checked = state.isPeriodicalTrimEnabled,
                        summary = stringResource(R.string.delete_old_backups_summary),
                        onCheckedChange = onPeriodicalTrimChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.max_backups_count),
                        value = state.periodicalBackupCount,
                        valueRange = 1..32,
                        step = 1,
                        enabled = state.isPeriodicalTrimEnabled,
                        valueText = { it.toString() },
                        onValueChange = onPeriodicalBackupCountChange,
                    )
                    state.lastBackupSummary?.let {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.create_backup),
                            summary = it,
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.create_backup),
                        summary = stringResource(R.string.backup_information),
                        onClick = onCreateBackupClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.restore_backup),
                        summary = stringResource(R.string.restore_summary),
                        onClick = onRestoreBackupClick,
                    )
                }
            }
            item(key = "external_backup_import") {
                SettingsPreferenceSection(title = stringResource(R.string.import_backup_from_other_apps)) {
                    SettingsActionPreference(
                        title = stringResource(R.string.export_mihon_backup),
                        summary = stringResource(R.string.export_mihon_backup_summary),
                        onClick = onExportMihonBackupClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.export_aniyomi_backup),
                        summary = stringResource(R.string.export_aniyomi_backup_summary),
                        onClick = onExportAniyomiBackupClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.import_backup_from_other_apps),
                        summary = stringResource(R.string.import_backup_from_other_apps_summary),
                        onClick = onImportExternalBackupClick,
                    )
                    SettingsSectionDivider()
                    SettingsInfoPreference(
                        title = stringResource(R.string.supported_apps),
                        summary = stringResource(R.string.import_backup_supported_apps_summary),
                        iconRes = R.drawable.ic_info_outline,
                    )
                    SettingsSectionDivider()
                    SettingsInfoPreference(
                        title = stringResource(R.string.read_more),
                        summary = stringResource(R.string.import_backup_scope_summary),
                        iconRes = R.drawable.ic_info_outline,
                    )
                }
            }
            item(key = "webdav_backup") {
                SettingsPreferenceSection(title = stringResource(R.string.webdav_integration)) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.sync_webdav_enable),
                        checked = state.isWebDavEnabled,
                        summary = stringResource(R.string.sync_webdav_enable_summary),
                        onCheckedChange = onWebDavEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_server_url),
                        value = state.webDavServerUrl,
                        enabled = state.isWebDavEnabled,
                        placeholder = "https://example.com/dav",
                        onValueChange = onWebDavServerUrlChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_username),
                        value = state.webDavUsername,
                        enabled = state.isWebDavEnabled,
                        placeholder = stringResource(R.string.username),
                        onValueChange = onWebDavUsernameChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_password),
                        value = state.webDavPassword,
                        enabled = state.isWebDavEnabled,
                        isPassword = true,
                        onValueChange = onWebDavPasswordChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_remote_path),
                        value = state.webDavRemotePath,
                        enabled = state.isWebDavEnabled,
                        placeholder = "/backup",
                        onValueChange = onWebDavRemotePathChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.test_connection),
                        summary = stringResource(R.string.webdav_integration),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading,
                        onClick = onWebDavTestClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_upload_now),
                        summary = state.webDavUploadBusySummary ?: stringResource(R.string.create_backup),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavUploadNowClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_restore_now),
                        summary = state.webDavRestoreBusySummary ?: stringResource(R.string.restore_backup),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavRestoreNowClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_remote_backups_refresh),
                        summary = state.webDavRemoteBackupBusySummary ?: stringResource(R.string.webdav_remote_backups_refresh_summary),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavRefreshRemoteBackupsClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_remote_backups_inspect),
                        summary = stringResource(R.string.webdav_remote_backups_inspect_summary),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavInspectRemoteBackupsClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_remote_backups_clear),
                        summary = stringResource(R.string.webdav_remote_backups_clear_summary),
                        enabled = state.isWebDavEnabled &&
                            !state.isWebDavCheckLoading &&
                            !state.isWebDavBusy &&
                            state.webDavRemoteBackups.isNotEmpty(),
                        showChevron = false,
                        onClick = { isClearRemoteBackupsConfirmVisible = true },
                    )
                    state.webDavRemoteBackups.forEach { backup ->
                        SettingsSectionDivider()
                        SettingsActionPreference(
                            title = backup.title,
                            summary = backup.summary,
                            enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                            onClick = { selectedRemoteBackup = backup },
                        )
                    }
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_auto_restore),
                        checked = state.isWebDavAutoRestoreEnabled,
                        summary = stringResource(R.string.webdav_auto_restore_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavAutoRestoreChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_keep_local_copy),
                        checked = state.isWebDavKeepLocalCopyEnabled,
                        summary = stringResource(R.string.webdav_keep_local_copy_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavKeepLocalCopyChange,
                    )
                    state.webDavLastActionSummary?.let {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.recent_webdav_action),
                            summary = it,
                        )
                    }
                    if (state.isWebDavPolicyNoteVisible) {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.read_more),
                            summary = stringResource(R.string.backup_periodic_explain_keep_local_copy_off),
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                    if (state.isWebDavBusy) {
                        SettingsSectionDivider()
                        val busyText = state.webDavUploadBusySummary ?: state.webDavRestoreBusySummary ?: ""
                        SettingsInfoPreference(
                            title = stringResource(R.string.processing_),
                            summary = state.webDavRemoteBackupBusySummary ?: busyText,
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                }
            }
        }
        if (state.isExternalImportDialogVisible) {
            AlertDialog(
                onDismissRequest = onDismissExternalImportDialog,
                title = { Text(text = stringResource(R.string.import_backup_choose_source_app)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = stringResource(R.string.import_backup_supported_apps_summary))
                        HorizontalDivider()
                        ExternalBackupApp.entries.forEach { app ->
                            TextButton(
                                onClick = { onImportExternalBackupAppClick(app) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = app.displayName())
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismissExternalImportDialog) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        selectedRemoteBackup?.let { backup ->
            AlertDialog(
                onDismissRequest = { selectedRemoteBackup = null },
                title = { Text(text = backup.title) },
                text = { Text(text = backup.summary) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            selectedRemoteBackup = null
                            onWebDavRestoreRemoteBackupClick(backup.file)
                        },
                        enabled = backup.restoreStatus != WebDavRemoteBackupRestoreStatus.UNRESTORABLE,
                    ) {
                        Text(text = stringResource(R.string.restore_backup))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            selectedRemoteBackup = null
                            onWebDavDeleteRemoteBackupClick(backup.file)
                        },
                    ) {
                        Text(text = stringResource(R.string.delete))
                    }
                    TextButton(onClick = { selectedRemoteBackup = null }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        if (isClearRemoteBackupsConfirmVisible) {
            AlertDialog(
                onDismissRequest = { isClearRemoteBackupsConfirmVisible = false },
                title = { Text(text = stringResource(R.string.webdav_remote_backups_clear)) },
                text = { Text(text = stringResource(R.string.webdav_remote_backups_clear_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isClearRemoteBackupsConfirmVisible = false
                            onWebDavClearRemoteBackupsClick()
                        },
                    ) {
                        Text(text = stringResource(R.string.clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isClearRemoteBackupsConfirmVisible = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

private fun ExternalBackupApp.displayName(): String = when (this) {
    ExternalBackupApp.MIHON -> "Mihon"
    ExternalBackupApp.KOMIKKU -> "Komikku"
    ExternalBackupApp.VENERA -> "Venera"
    ExternalBackupApp.ANIYOMI -> "Aniyomi"
    ExternalBackupApp.ANIKKU -> "Anikku"
    ExternalBackupApp.ANIMIRU -> "Animiru"
}
