package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@Composable
internal fun VideoPlayerInfoDialog(
	details: String,
	onDismissRequest: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(stringResource(R.string.video_detail)) },
		text = {
			SelectionContainer {
				Text(
					text = details,
					fontFamily = FontFamily.Monospace,
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 480.dp)
						.verticalScroll(rememberScrollState()),
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onDismissRequest) {
				Text(stringResource(android.R.string.ok))
			}
		},
	)
}
