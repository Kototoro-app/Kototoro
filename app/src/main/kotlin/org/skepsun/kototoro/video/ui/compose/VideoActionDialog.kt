package org.skepsun.kototoro.video.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal data class VideoActionDialogItem(
	val title: String,
	val subtitle: String? = null,
	val leadingText: String? = null,
	@DrawableRes val iconRes: Int? = null,
	val checked: Boolean? = null,
	val onClick: () -> Unit,
)

internal data class VideoActionDialogState(
	val title: String,
	val items: List<VideoActionDialogItem>,
)

@Composable
internal fun VideoActionDialog(
	state: VideoActionDialogState,
	onDismissRequest: () -> Unit,
	onItemSelected: (VideoActionDialogItem) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(state.title) },
		text = {
			LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
				items(state.items) { item ->
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.fillMaxWidth().clickable { onItemSelected(item) }.padding(vertical = 10.dp),
					) {
						item.iconRes?.let { Icon(painterResource(it), null, Modifier.size(24.dp)) }
						item.leadingText?.let { Text(it, modifier = Modifier.padding(end = 12.dp)) }
						Column(modifier = Modifier.weight(1f).padding(start = if (item.iconRes != null) 12.dp else 0.dp)) {
							Text(item.title)
							item.subtitle?.takeIf(String::isNotBlank)?.let { Text(it) }
						}
						item.checked?.takeIf { it }?.let { Icon(Icons.Filled.Check, null) }
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = {
			TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
