package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class VideoGestureOverlayState(
	val left: String? = null,
	val right: String? = null,
	val center: String? = null,
)

@Composable
internal fun VideoGestureOverlays(state: VideoGestureOverlayState) {
	Box(modifier = Modifier.fillMaxSize()) {
		state.left?.let {
			GestureLabel(it, Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
		}
		state.right?.let {
			GestureLabel(it, Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
		}
		state.center?.let {
			GestureLabel(it, Modifier.align(Alignment.Center))
		}
	}
}

@Composable
private fun GestureLabel(text: String, modifier: Modifier) {
	Text(
		text = text,
		color = Color.White,
		style = MaterialTheme.typography.bodyLarge,
		modifier = modifier
			.background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(6.dp))
			.padding(horizontal = 14.dp, vertical = 10.dp),
	)
}
