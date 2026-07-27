package org.skepsun.kototoro.settings.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

@Composable
fun ContentDirectorySelectRoute(
    contentType: String,
    onDismiss: () -> Unit,
    onError: (Throwable) -> Unit,
    viewModel: ContentDirectorySelectViewModel = hiltViewModel(key = "directory-$contentType"),
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onCustomDirectoryPicked)
    }
    LaunchedEffect(contentType) { viewModel.initialize(contentType) }
    LaunchedEffect(viewModel) { viewModel.onDismissDialog.collect { onDismiss() } }
    LaunchedEffect(viewModel) { viewModel.onPickDirectory.collect { picker.launch(null) } }
    LaunchedEffect(viewModel) {
        viewModel.onError.collect { event ->
            event?.consume { error -> onError(error) }
        }
    }
    val entries by viewModel.items.collectAsStateWithLifecycle()
    val title = when (contentType) {
        ContentDirectorySelectViewModel.CONTENT_TYPE_NOVEL -> R.string.novel_save_location
        ContentDirectorySelectViewModel.CONTENT_TYPE_VIDEO -> R.string.video_save_location
        else -> R.string.manga_save_location
    }
    SettingsAlertDialog(
        title = stringResource(title),
        onDismissRequest = onDismiss,
        text = {
            LazyColumn {
                items(entries, key = { it.title ?: it.titleRes.toString() }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.onItemClick(item) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = item.isChecked, onClick = null)
                        Text(item.title ?: stringResource(item.titleRes))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
    )
}
