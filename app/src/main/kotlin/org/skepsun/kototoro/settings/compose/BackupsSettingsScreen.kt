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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.external.ExternalBackupApp

data class BackupsSettingsUiState(
    val backupOutputSummary: String,
    val isBackupOutputInvalid: Boolean,
    val backupFrequency: Float,
    val isPeriodicalTrimEnabled: Boolean,
    val periodicalBackupCount: Int,
    val lastBackupSummary: String?,
    val isExternalImportDialogVisible: Boolean,
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
) {
    Scaffold(
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
                start = 16.dp,
                end = 16.dp,
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
