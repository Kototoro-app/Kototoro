package org.skepsun.kototoro.backups.ui.restore

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupRestoreFormat

@Composable
fun RestoreDialogRoute(
    uri: Uri,
    restoreFormat: BackupRestoreFormat,
    onRestoreStarted: () -> Unit,
    onUnsupported: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(key = "restore-${restoreFormat.name}-${uri.hashCode()}"),
) {
    LaunchedEffect(uri, restoreFormat) { viewModel.initialize(uri, restoreFormat) }
    LaunchedEffect(viewModel.onError) {
        viewModel.onError.collect { event ->
            event?.consume {
                onDismiss()
                onUnsupported()
            }
        }
    }
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    val entries by viewModel.availableEntries.collectAsStateWithLifecycle()
    val restoreMode by viewModel.restoreMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(
                    when (restoreFormat) {
                        BackupRestoreFormat.KOTOTORO_CURRENT -> R.string.restore_kototoro_backup
                        BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO ->
                            R.string.import_kotatsu_or_legacy_backup
                    },
                ),
            )
        },
        text = {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Column {
                    if (restoreFormat == BackupRestoreFormat.KOTOTORO_CURRENT) {
                        RestoreModeSelector(
                            selected = restoreMode,
                            onSelect = viewModel::onRestoreModeChange,
                        )
                    }
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(entries, key = { it.section }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = item.isEnabled) { viewModel.onItemClick(item) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = item.isChecked,
                                    enabled = item.isEnabled,
                                    onCheckedChange = { viewModel.onItemClick(item) },
                                )
                                Text(stringResource(item.titleResId))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && entries.any { it.isChecked },
                onClick = {
                    val mode = restoreMode.takeIf { restoreFormat == BackupRestoreFormat.KOTOTORO_CURRENT }
                    if (RestoreService.start(context, uri, viewModel.getCheckedSections(), restoreFormat, mode)) {
                        onRestoreStarted()
                    } else {
                        onUnsupported()
                    }
                },
            ) { Text(stringResource(R.string.restore)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun RestoreModeSelector(
    selected: BackupRepository.RestoreMode,
    onSelect: (BackupRepository.RestoreMode) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        RestoreModeOption(
            titleResId = R.string.restore_mode_replace_existing,
            summaryResId = R.string.restore_mode_replace_existing_summary,
            isSelected = selected == BackupRepository.RestoreMode.SNAPSHOT_REPLACE,
            onClick = { onSelect(BackupRepository.RestoreMode.SNAPSHOT_REPLACE) },
        )
        RestoreModeOption(
            titleResId = R.string.restore_mode_merge_with_existing,
            summaryResId = R.string.restore_mode_merge_with_existing_summary,
            isSelected = selected == BackupRepository.RestoreMode.MERGE,
            onClick = { onSelect(BackupRepository.RestoreMode.MERGE) },
        )
    }
}

@Composable
private fun RestoreModeOption(
    titleResId: Int,
    summaryResId: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Column {
            Text(stringResource(titleResId), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(summaryResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
