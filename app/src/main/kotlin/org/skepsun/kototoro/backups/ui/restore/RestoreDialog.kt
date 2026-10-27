package org.skepsun.kototoro.backups.ui.restore

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.backups.BackupRestoreMode
import org.skepsun.kototoro.core.ui.backups.BackupRestoreModeList
import org.skepsun.kototoro.core.ui.backups.BackupRestoreSection
import org.skepsun.kototoro.core.ui.backups.BackupRestoreSectionList

@Composable
fun RestoreDialogRoute(
    uri: Uri,
    onRestoreStarted: () -> Unit,
    onUnsupported: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(key = "restore-${uri.hashCode()}"),
) {
    LaunchedEffect(uri) { viewModel.initialize(uri) }
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
    // The shared rows only carry ids, so the platform keeps the mapping back to its own models.
    val entriesById = entries.associateBy { it.section.name }
    val modesById = BackupRepository.RestoreMode.entries.associateBy { it.name }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(R.string.restore_kototoro_backup))
        },
        text = {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Column {
                    BackupRestoreModeList(
                        modes = BackupRepository.RestoreMode.entries.map { mode ->
                            BackupRestoreMode(
                                id = mode.name,
                                title = stringResource(mode.restoreModeTitleRes),
                                summary = stringResource(mode.restoreModeSummaryRes),
                            )
                        },
                        selectedId = restoreMode.name,
                        enabled = true,
                        onSelect = { id -> modesById[id]?.let(viewModel::onRestoreModeChange) },
                        modifier = Modifier.padding(bottom = 4.dp),
                        tagPrefix = "restore-mode",
                    )
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        item {
                            BackupRestoreSectionList(
                                sections = entries.map { item ->
                                    BackupRestoreSection(
                                        id = item.section.name,
                                        title = stringResource(item.titleResId),
                                        checked = item.isChecked,
                                        enabled = item.isEnabled,
                                    )
                                },
                                enabled = true,
                                onToggle = { id -> entriesById[id]?.let(viewModel::onItemClick) },
                                tagPrefix = "restore-section",
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TvTextButton(
                enabled = !loading && entries.any { it.isChecked },
                onClick = {
                    if (RestoreService.start(context, uri, viewModel.getCheckedSections(), restoreMode)) {
                        onRestoreStarted()
                    } else {
                        onUnsupported()
                    }
                },
            ) { Text(stringResource(R.string.restore)) }
        },
        dismissButton = {
            TvTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** The two restore modes' Android strings, next to the enum so a new mode cannot be added without its labels. */
private val BackupRepository.RestoreMode.restoreModeTitleRes: Int
    get() = when (this) {
        BackupRepository.RestoreMode.SNAPSHOT_REPLACE -> R.string.restore_mode_replace_existing
        BackupRepository.RestoreMode.MERGE -> R.string.restore_mode_merge_with_existing
    }

private val BackupRepository.RestoreMode.restoreModeSummaryRes: Int
    get() = when (this) {
        BackupRepository.RestoreMode.SNAPSHOT_REPLACE -> R.string.restore_mode_replace_existing_summary
        BackupRepository.RestoreMode.MERGE -> R.string.restore_mode_merge_with_existing_summary
    }
