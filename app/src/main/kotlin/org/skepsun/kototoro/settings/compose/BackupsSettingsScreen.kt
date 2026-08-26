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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.skepsun.kototoro.backups.ui.periodical.ManualWebDavRestoreMode
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
    val isGoogleDriveSyncEnabled: Boolean,
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
    onImportKotatsuOrLegacyBackupClick: () -> Unit,
    onExportKotatsuBackupClick: () -> Unit,
    onExportMihonBackupClick: () -> Unit,
    onExportAniyomiBackupClick: () -> Unit,
    onExportUsagiBackupClick: () -> Unit,
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
    onWebDavRestoreNowClick: (ManualWebDavRestoreMode) -> Unit,
    onWebDavRefreshRemoteBackupsClick: () -> Unit,
    onWebDavInspectRemoteBackupsClick: () -> Unit,
    onWebDavRestoreRemoteBackupClick: (BackupFileInfo, ManualWebDavRestoreMode) -> Unit,
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
        var pendingRestoreRemoteBackup by remember { mutableStateOf<WebDavRemoteBackupUiItem?>(null) }
        var isRestoreLatestModeDialogVisible by rememberSaveable { mutableStateOf(false) }
        var isClearRemoteBackupsConfirmVisible by rememberSaveable { mutableStateOf(false) }
        var isEnableWebDavConfirmVisible by rememberSaveable { mutableStateOf(false) }
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
            item(key = "backup_restore") {
                SettingsPreferenceGroup(title = backupRestoreTitle) {
                    item { SettingsActionPreference(
                        title = stringResource(R.string.backups_output_directory),
                        summary = state.backupOutputSummary,
                        iconRes = if (state.isBackupOutputInvalid) {
                            R.drawable.ic_alert_outline
                        } else {
                            R.drawable.ic_folder_file
                        },
                        onClick = onBackupOutputClick,
                    ) }
                    item { SettingsChoicePreference(
                        title = stringResource(R.string.backup_frequency),
                        iconRes = R.drawable.ic_schedule,
                        value = state.backupFrequency,
                        options = backupFrequencyOptions,
                        onValueChange = onBackupFrequencyChange,
                    ) }
                    item { SettingsSwitchPreference(
                        title = stringResource(R.string.delete_old_backups),
                        iconRes = R.drawable.ic_delete,
                        checked = state.isPeriodicalTrimEnabled,
                        summary = stringResource(R.string.delete_old_backups_summary),
                        onCheckedChange = onPeriodicalTrimChange,
                    ) }
                    item { SettingsSliderPreference(
                        title = stringResource(R.string.max_backups_count),
                        iconRes = R.drawable.ic_timeline,
                        value = state.periodicalBackupCount,
                        valueRange = 1..32,
                        step = 1,
                        enabled = state.isPeriodicalTrimEnabled,
                        valueText = { it.toString() },
                        onValueChange = onPeriodicalBackupCountChange,
                    ) }
                    state.lastBackupSummary?.let {
                        item { SettingsInfoPreference(
                            title = stringResource(R.string.create_backup),
                            summary = it,
                            iconRes = R.drawable.ic_info_outline,
                        ) }
                    }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.create_backup),
                        summary = stringResource(R.string.backup_information),
                        iconRes = R.drawable.ic_backup_restore,
                        onClick = onCreateBackupClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.restore_kototoro_backup),
                        summary = stringResource(R.string.restore_kototoro_backup_summary),
                        iconRes = R.drawable.ic_revert,
                        onClick = onRestoreBackupClick,
                    ) }
                }
            }
            item(key = "external_backup_import") {
                SettingsPreferenceGroup(title = stringResource(R.string.external_backup_section_title)) {
                    item { SettingsActionPreference(
                        title = stringResource(R.string.import_kotatsu_or_legacy_backup),
                        summary = stringResource(R.string.import_kotatsu_or_legacy_backup_summary),
                        iconRes = R.drawable.ic_import,
                        onClick = onImportKotatsuOrLegacyBackupClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.export_kotatsu_backup),
                        summary = stringResource(R.string.export_kotatsu_backup_summary),
                        iconRes = R.drawable.ic_share,
                        onClick = onExportKotatsuBackupClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.export_mihon_backup),
                        summary = stringResource(R.string.export_mihon_backup_summary),
                        iconRes = R.drawable.ic_share,
                        onClick = onExportMihonBackupClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.export_aniyomi_backup),
                        summary = stringResource(R.string.export_aniyomi_backup_summary),
                        iconRes = R.drawable.ic_share,
                        onClick = onExportAniyomiBackupClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.export_usagi_backup),
                        summary = stringResource(R.string.export_usagi_backup_summary),
                        iconRes = R.drawable.ic_share,
                        onClick = onExportUsagiBackupClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.import_backup_from_other_apps),
                        iconRes = R.drawable.ic_import,
                        summary = stringResource(
                            R.string.import_backup_from_other_apps_combined_summary,
                            stringResource(R.string.import_backup_from_other_apps_summary),
                            stringResource(R.string.supported_apps),
                            stringResource(R.string.import_backup_supported_apps_summary),
                        ),
                        onClick = onImportExternalBackupClick,
                    ) }
                }
            }
            item(key = "webdav_backup") {
                SettingsPreferenceGroup(title = stringResource(R.string.webdav_integration)) {
                    item { SettingsSwitchPreference(
                        title = stringResource(R.string.sync_webdav_enable),
                        iconRes = R.drawable.ic_cloud_upload,
                        checked = state.isWebDavEnabled,
                        summary = stringResource(R.string.sync_webdav_enable_summary),
                        onCheckedChange = { enabled ->
                            if (enabled && state.isGoogleDriveSyncEnabled) {
                                isEnableWebDavConfirmVisible = true
                            } else {
                                onWebDavEnabledChange(enabled)
                            }
                        },
                    ) }
                    item { SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_server_url),
                        iconRes = R.drawable.ic_web,
                        value = state.webDavServerUrl,
                        enabled = state.isWebDavEnabled,
                        placeholder = "https://example.com/dav",
                        onValueChange = onWebDavServerUrlChange,
                    ) }
                    item { SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_username),
                        iconRes = R.drawable.ic_user,
                        value = state.webDavUsername,
                        enabled = state.isWebDavEnabled,
                        placeholder = stringResource(R.string.username),
                        onValueChange = onWebDavUsernameChange,
                    ) }
                    item { SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_password),
                        iconRes = R.drawable.ic_key,
                        value = state.webDavPassword,
                        enabled = state.isWebDavEnabled,
                        isPassword = true,
                        onValueChange = onWebDavPasswordChange,
                    ) }
                    item { SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_remote_path),
                        iconRes = R.drawable.ic_folder_file,
                        value = state.webDavRemotePath,
                        enabled = state.isWebDavEnabled,
                        placeholder = "/backup",
                        onValueChange = onWebDavRemotePathChange,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.test_connection),
                        iconRes = R.drawable.ic_plug,
                        summary = stringResource(R.string.webdav_integration),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading,
                        onClick = onWebDavTestClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.webdav_upload_now),
                        iconRes = R.drawable.ic_cloud_upload,
                        summary = state.webDavUploadBusySummary ?: stringResource(R.string.create_backup),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavUploadNowClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.webdav_restore_now),
                        iconRes = R.drawable.ic_cloud_download,
                        summary = state.webDavRestoreBusySummary ?: stringResource(R.string.restore_backup),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = { isRestoreLatestModeDialogVisible = true },
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.webdav_remote_backups_refresh),
                        iconRes = R.drawable.ic_sync,
                        summary = state.webDavRemoteBackupBusySummary
                            ?: stringResource(R.string.webdav_remote_backups_refresh_summary),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavRefreshRemoteBackupsClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.webdav_remote_backups_inspect),
                        iconRes = R.drawable.ic_list_detailed,
                        summary = stringResource(R.string.webdav_remote_backups_inspect_summary),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                        onClick = onWebDavInspectRemoteBackupsClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.webdav_remote_backups_clear),
                        iconRes = R.drawable.ic_delete_all,
                        summary = stringResource(R.string.webdav_remote_backups_clear_summary),
                        enabled = state.isWebDavEnabled &&
                            !state.isWebDavCheckLoading &&
                            !state.isWebDavBusy &&
                            state.webDavRemoteBackups.isNotEmpty(),
                        showChevron = false,
                        onClick = { isClearRemoteBackupsConfirmVisible = true },
                    ) }
                    state.webDavRemoteBackups.forEach { backup ->
                        item { SettingsActionPreference(
                            title = backup.title,
                            iconRes = R.drawable.ic_file_zip,
                            summary = backup.summary,
                            enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading && !state.isWebDavBusy,
                            onClick = { selectedRemoteBackup = backup },
                        ) }
                    }
                    item { SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_auto_restore),
                        iconRes = R.drawable.ic_sync,
                        checked = state.isWebDavAutoRestoreEnabled,
                        summary = stringResource(R.string.webdav_auto_restore_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavAutoRestoreChange,
                    ) }
                    item { SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_keep_local_copy),
                        iconRes = R.drawable.ic_save,
                        checked = state.isWebDavKeepLocalCopyEnabled,
                        summary = stringResource(R.string.webdav_keep_local_copy_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavKeepLocalCopyChange,
                    ) }
                    state.webDavLastActionSummary?.let {
                        item { SettingsInfoPreference(
                            title = stringResource(R.string.recent_webdav_action),
                            iconRes = R.drawable.ic_info_outline,
                            summary = it,
                        ) }
                    }
                    if (state.isWebDavPolicyNoteVisible) {
                        item { SettingsInfoPreference(
                            title = stringResource(R.string.read_more),
                            summary = stringResource(R.string.backup_periodic_explain_keep_local_copy_off),
                            iconRes = R.drawable.ic_info_outline,
                        ) }
                    }
                    if (state.isWebDavBusy) {
                        val busyText = state.webDavUploadBusySummary ?: state.webDavRestoreBusySummary ?: ""
                        item { SettingsInfoPreference(
                            title = stringResource(R.string.processing_),
                            summary = state.webDavRemoteBackupBusySummary ?: busyText,
                            iconRes = R.drawable.ic_info_outline,
                        ) }
                    }
                }
            }
        }
        if (state.isExternalImportDialogVisible) {
            SettingsAlertDialog(
                onDismissRequest = onDismissExternalImportDialog,
                title = stringResource(R.string.import_backup_choose_source_app),
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = stringResource(R.string.import_backup_supported_apps_summary))
                        HorizontalDivider()
                        ExternalBackupApp.entries.forEach { app ->
                            SettingsDialogActionButton(
                                text = app.displayName(),
                                onClick = { onImportExternalBackupAppClick(app) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            app.guideNoteRes()?.let { noteRes ->
                                Text(
                                    text = stringResource(noteRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    SettingsDialogActionButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismissExternalImportDialog,
                    )
                },
            )
        }
        selectedRemoteBackup?.let { backup ->
            SettingsAlertDialog(
                onDismissRequest = { selectedRemoteBackup = null },
                title = backup.title,
                text = { Text(text = backup.summary) },
                confirmButton = {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.restore_backup),
                        onClick = {
                            selectedRemoteBackup = null
                            pendingRestoreRemoteBackup = backup
                        },
                        enabled = backup.restoreStatus != WebDavRemoteBackupRestoreStatus.UNRESTORABLE,
                    )
                },
                dismissButton = {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.delete),
                        onClick = {
                            selectedRemoteBackup = null
                            onWebDavDeleteRemoteBackupClick(backup.file)
                        },
                    )
                    SettingsDialogActionButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { selectedRemoteBackup = null },
                    )
                },
            )
        }
        if (isRestoreLatestModeDialogVisible) {
            WebDavRestoreModeDialog(
                onDismissRequest = { isRestoreLatestModeDialogVisible = false },
                onModeSelected = { mode ->
                    isRestoreLatestModeDialogVisible = false
                    onWebDavRestoreNowClick(mode)
                },
            )
        }
        pendingRestoreRemoteBackup?.let { backup ->
            WebDavRestoreModeDialog(
                onDismissRequest = { pendingRestoreRemoteBackup = null },
                onModeSelected = { mode ->
                    pendingRestoreRemoteBackup = null
                    onWebDavRestoreRemoteBackupClick(backup.file, mode)
                },
            )
        }
        if (isClearRemoteBackupsConfirmVisible) {
            SettingsAlertDialog(
                onDismissRequest = { isClearRemoteBackupsConfirmVisible = false },
                title = stringResource(R.string.webdav_remote_backups_clear),
                text = { Text(text = stringResource(R.string.webdav_remote_backups_clear_confirm)) },
                confirmButton = {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.clear),
                        onClick = {
                            isClearRemoteBackupsConfirmVisible = false
                            onWebDavClearRemoteBackupsClick()
                        },
                    )
                },
                dismissButton = {
                    SettingsDialogActionButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { isClearRemoteBackupsConfirmVisible = false },
                    )
                },
            )
        }
        if (isEnableWebDavConfirmVisible) {
            SettingsAlertDialog(
                onDismissRequest = { isEnableWebDavConfirmVisible = false },
                title = stringResource(R.string.sync_backend_switch_webdav_title),
                text = { Text(text = stringResource(R.string.sync_backend_switch_webdav_confirm)) },
                confirmButton = {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.enable),
                        onClick = {
                            isEnableWebDavConfirmVisible = false
                            onWebDavEnabledChange(true)
                        },
                    )
                },
                dismissButton = {
                    SettingsDialogActionButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { isEnableWebDavConfirmVisible = false },
                    )
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

/** Optional guidance shown under an import app row about built-in-source remapping. */
private fun ExternalBackupApp.guideNoteRes(): Int? = when (this) {
    ExternalBackupApp.MIHON -> R.string.import_backup_mihon_note
    ExternalBackupApp.KOMIKKU -> R.string.import_backup_komikku_note
    else -> null
}

@Composable
private fun WebDavRestoreModeDialog(
    onDismissRequest: () -> Unit,
    onModeSelected: (ManualWebDavRestoreMode) -> Unit,
) {
    SettingsAlertDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.webdav_restore_mode_title),
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.webdav_restore_mode_summary))
                HorizontalDivider()
                SettingsDialogActionButton(
                    text = stringResource(R.string.webdav_restore_mode_replace),
                    onClick = { onModeSelected(ManualWebDavRestoreMode.SNAPSHOT_REPLACE) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.webdav_restore_mode_merge),
                    onClick = { onModeSelected(ManualWebDavRestoreMode.MERGE) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismissRequest,
            )
        },
    )
}
