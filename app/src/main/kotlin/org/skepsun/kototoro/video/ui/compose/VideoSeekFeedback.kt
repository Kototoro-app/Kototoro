package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class VideoSeekFeedbackState(
	val text: String,
	val progress: Float,
)

@Composable
internal fun VideoSeekFeedback(state: VideoSeekFeedbackState) {
	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier
				.width(200.dp)
				.background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(6.dp))
				.padding(16.dp),
		) {
			Text(
				text = state.text,
				color = Color.White,
				style = MaterialTheme.typography.titleMedium,
				textAlign = TextAlign.Center,
			)
			LinearProgressIndicator(
				progress = { state.progress.coerceIn(0f, 1f) },
				color = MaterialTheme.colorScheme.tertiary,
				trackColor = Color.White.copy(alpha = 0.27f),
				modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
			)
		}
	}
}
