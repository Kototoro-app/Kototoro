package org.skepsun.kototoro.local.ui.compose

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.importer.ImportMode
import org.skepsun.kototoro.local.data.importer.LocalImportKind
import org.skepsun.kototoro.local.ui.ImportService

private enum class ImportContentType(
    val kind: LocalImportKind?,
    val titleRes: Int,
) {
    AUTO(null, R.string.import_content_type_auto),
    MANGA(LocalImportKind.MANGA, R.string.manga),
    NOVEL(LocalImportKind.NOVEL, R.string.novel),
    VIDEO(LocalImportKind.VIDEO, R.string.video),
}

private data class ImportOption(
    val iconRes: Int,
    val titleRes: Int,
    val subtitleRes: Int,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImportDialogEntryPoint {
    val storageManager: LocalStorageManager
}

@Composable
fun ImportDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication<ImportDialogEntryPoint>(context.applicationContext)
    }
    val storageManager = entryPoint.storageManager
    var selectedType by rememberSaveable { mutableStateOf(ImportContentType.AUTO) }

    fun showResult(isStarted: Boolean) {
        Toast.makeText(
            context,
            if (isStarted) R.string.import_will_start_soon else R.string.error_occurred,
            Toast.LENGTH_LONG,
        ).show()
    }

    fun startImportFiles(uris: Collection<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        uris.forEach { uri -> storageManager.takePermissions(uri, isReadOnly = true) }
        showResult(ImportService.start(context, uris, selectedType.kind))
        onDismissRequest()
    }

    fun startImportDirectory(uri: Uri?, mode: ImportMode) {
        if (uri == null) {
            return
        }
        storageManager.takePermissions(uri, isReadOnly = true)
        showResult(ImportService.start(context, uri, mode, selectedType.kind))
        onDismissRequest()
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        startImportFiles(it)
    }
    val importSingleDirectoryLauncher = rememberLauncherForActivityResult(
        OpenDocumentTreeHelper.OpenDocumentTreeContract(),
    ) {
        startImportDirectory(it, ImportMode.SINGLE_MANGA)
    }
    val importMultipleDirectoryLauncher = rememberLauncherForActivityResult(
        OpenDocumentTreeHelper.OpenDocumentTreeContract(),
    ) {
        startImportDirectory(it, ImportMode.MULTIPLE_MANGA)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(R.string._import))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ImportTypeSelector(
                    selectedType = selectedType,
                    onSelectedTypeChange = { selectedType = it },
                )
                Column {
                    ImportOptionItem(
                        option = selectedType.fileOption(),
                        onClick = {
                            if (!importFileLauncher.tryLaunch(arrayOf("*/*"))) {
                                Toast.makeText(context, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    ImportOptionItem(
                        option = selectedType.singleDirectoryOption(),
                        onClick = {
                            if (!importSingleDirectoryLauncher.tryLaunch(null)) {
                                Toast.makeText(context, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    ImportOptionItem(
                        option = selectedType.multipleDirectoryOption(),
                        onClick = {
                            if (!importMultipleDirectoryLauncher.tryLaunch(null)) {
                                Toast.makeText(context, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ImportTypeSelector(
    selectedType: ImportContentType,
    onSelectedTypeChange: (ImportContentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.selectableGroup()) {
        Text(
            text = stringResource(R.string.import_content_type),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ImportContentType.entries.take(2).forEach { type ->
                ImportTypeChip(
                    type = type,
                    selected = selectedType == type,
                    onClick = { onSelectedTypeChange(type) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ImportContentType.entries.drop(2).forEach { type ->
                ImportTypeChip(
                    type = type,
                    selected = selectedType == type,
                    onClick = { onSelectedTypeChange(type) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ImportTypeChip(
    type: ImportContentType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = stringResource(type.titleRes),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        modifier = modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
    )
}

@Composable
private fun ImportOptionItem(
    option: ImportOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            painter = painterResource(option.iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(option.titleRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(option.subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ImportContentType.fileOption(): ImportOption = when (this) {
    ImportContentType.MANGA -> ImportOption(
        iconRes = R.drawable.ic_file_zip,
        titleRes = R.string.comics_archive,
        subtitleRes = R.string.import_file_description_manga,
    )
    ImportContentType.NOVEL -> ImportOption(
        iconRes = R.drawable.ic_file_zip,
        titleRes = R.string.import_file,
        subtitleRes = R.string.import_file_description_novel,
    )
    ImportContentType.VIDEO -> ImportOption(
        iconRes = R.drawable.ic_file_zip,
        titleRes = R.string.import_file,
        subtitleRes = R.string.import_file_description_video,
    )
    ImportContentType.AUTO -> ImportOption(
        iconRes = R.drawable.ic_file_zip,
        titleRes = R.string.import_file,
        subtitleRes = R.string.import_file_description,
    )
}

private fun ImportContentType.singleDirectoryOption(): ImportOption = when (this) {
    ImportContentType.MANGA -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.folder_single_manga,
        subtitleRes = R.string.import_folder_single_description_manga,
    )
    ImportContentType.NOVEL -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.folder_single_novel,
        subtitleRes = R.string.import_folder_single_description_novel,
    )
    ImportContentType.VIDEO -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.folder_single_video,
        subtitleRes = R.string.import_folder_single_description_video,
    )
    ImportContentType.AUTO -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.import_folder_single,
        subtitleRes = R.string.import_folder_single_description,
    )
}

private fun ImportContentType.multipleDirectoryOption(): ImportOption = when (this) {
    ImportContentType.MANGA -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.folder_multiple_manga,
        subtitleRes = R.string.import_folder_multiple_description_manga,
    )
    ImportContentType.NOVEL -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.folder_multiple_novel,
        subtitleRes = R.string.import_folder_multiple_description_novel,
    )
    ImportContentType.VIDEO -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.folder_multiple_video,
        subtitleRes = R.string.import_folder_multiple_description_video,
    )
    ImportContentType.AUTO -> ImportOption(
        iconRes = R.drawable.ic_folder_file,
        titleRes = R.string.import_folder_multiple,
        subtitleRes = R.string.import_folder_multiple_description,
    )
}
