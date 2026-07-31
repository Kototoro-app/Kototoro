package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@Composable
internal fun VideoPlayerInfoDialog(
	details: String,
	onDismissRequest: () -> Unit,
) {
	val dialogShape = RoundedCornerShape(18.dp)
	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(
					text = stringResource(R.string.video_detail),
					color = Color.White,
					modifier = Modifier.weight(1f),
				)
				IconButton(
					onClick = onDismissRequest,
					modifier = Modifier.size(40.dp),
				) {
					Icon(
						imageVector = Icons.Default.Close,
						contentDescription = stringResource(R.string.close),
						tint = Color.White,
					)
				}
			}
		},
		text = {
			SelectionContainer {
				Text(
					text = details,
					fontFamily = FontFamily.Monospace,
					color = Color.White.copy(alpha = 0.82f),
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 480.dp)
						.verticalScroll(rememberScrollState()),
				)
			}
		},
		confirmButton = {},
		shape = dialogShape,
		containerColor = Color.Black.copy(alpha = 0.86f),
		titleContentColor = Color.White,
		textContentColor = Color.White.copy(alpha = 0.82f),
		tonalElevation = 0.dp,
		modifier = Modifier.border(
			width = 1.dp,
			color = Color.White.copy(alpha = 0.16f),
			shape = dialogShape,
		),
	)
}
