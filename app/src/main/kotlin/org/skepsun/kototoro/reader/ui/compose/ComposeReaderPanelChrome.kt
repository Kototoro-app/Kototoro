package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderPanelDragHandle(
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.fillMaxWidth()
			.height(32.dp)
			.pointerInput(onDismiss) {
				var dragDistance = 0f
				detectVerticalDragGestures(
					onVerticalDrag = { change, dragAmount ->
						if (dragAmount > 0f) {
							dragDistance += dragAmount
							change.consume()
						}
					},
					onDragEnd = {
						if (dragDistance >= 56.dp.toPx()) onDismiss()
						dragDistance = 0f
					},
					onDragCancel = { dragDistance = 0f },
				)
			},
	) {
		Box(
			modifier = Modifier
				.clip(RoundedCornerShape(50))
				.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f))
				.height(4.dp)
				.fillMaxWidth(0.12f),
		)
	}
}
