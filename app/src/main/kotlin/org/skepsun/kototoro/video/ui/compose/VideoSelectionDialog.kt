package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal data class VideoSelectionDialogState(
	val title: String,
	val options: List<String>,
	val selectedIndex: Int,
	val onSelect: (Int) -> Unit,
)

@Composable
internal fun VideoSelectionDialog(
	state: VideoSelectionDialogState,
	onDismissRequest: () -> Unit,
	onSelect: (Int) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(state.title) },
		text = {
			Column {
				state.options.forEachIndexed { index, label ->
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier
							.fillMaxWidth()
							.clickable { onSelect(index) }
							.padding(vertical = 6.dp),
					) {
						RadioButton(
							selected = index == state.selectedIndex,
							onClick = { onSelect(index) },
						)
						Text(text = label, modifier = Modifier.padding(start = 8.dp))
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = {
			TextButton(onClick = onDismissRequest) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}
