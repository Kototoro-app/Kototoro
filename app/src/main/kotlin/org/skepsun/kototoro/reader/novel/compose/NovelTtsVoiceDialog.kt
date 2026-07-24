package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

internal data class NovelTtsVoiceDialogState(
    val title: String,
    val entries: List<String>,
    val selectedIndex: Int,
    val onSelected: (Int) -> Unit,
    val onDismiss: () -> Unit,
    val onManage: (() -> Unit)? = null,
)

@Composable
internal fun NovelTtsVoiceDialog(state: NovelTtsVoiceDialogState) {
    AlertDialog(
        onDismissRequest = state.onDismiss,
        title = { Text(state.title) },
        text = {
            LazyColumn {
                itemsIndexed(state.entries) { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.onSelected(index) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = index == state.selectedIndex,
                            onClick = { state.onSelected(index) },
                        )
                        Text(entry)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            state.onManage?.let { onManage ->
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.tts_legado_manage_sources))
                }
            }
        },
    )
}
