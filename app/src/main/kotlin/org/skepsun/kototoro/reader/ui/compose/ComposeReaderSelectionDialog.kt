package org.skepsun.kototoro.reader.ui.compose

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

internal data class ReaderSelectionDialogState(
    val title: String,
    val entries: List<String>,
    val selectedIndex: Int? = null,
    val onSelected: (Int) -> Unit,
)

@Composable
internal fun ComposeReaderSelectionDialog(
    state: ReaderSelectionDialogState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                        if (state.selectedIndex != null) {
                            RadioButton(
                                selected = state.selectedIndex == index,
                                onClick = { state.onSelected(index) },
                            )
                        }
                        Text(entry, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
