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

/**
 * What the seeking bubble says and how far its bar is: the jump sentence, the new position and the length.
 * Hosts never build it by hand — [videoSeekFeedbackState] keeps both players on the same shape.
 */
data class VideoSeekFeedbackState(
    val text: String,
    val progress: Float,
)

/**
 * The bubble Android shows over the picture while seeking, for hosts that hand it their own numbers.
 *
 * [delta] is the localized jump sentence ("快进 10 秒"), already formatted by the host because it carries a number and
 * a grammar; pass null while dragging a slider, where no fixed jump applies. The clock pads the minutes and takes
 * its hour shape from the episode length, so the bubble of a long episode does not change width as it seeks.
 */
fun videoSeekFeedbackState(positionMs: Long, durationMs: Long, delta: String? = null): VideoSeekFeedbackState {
    val forceHours = durationMs >= 3_600_000L
    val position = formatPlaybackTime(positionMs, padMinutes = true, forceHours = forceHours)
    val duration = formatPlaybackTime(durationMs, padMinutes = true, forceHours = forceHours)
    val time = "$position / $duration"
    return VideoSeekFeedbackState(
        text = if (delta.isNullOrEmpty()) time else "$delta\n$time",
        progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
    )
}

/**
 * The seeking bubble itself: a translucent card in the middle of the picture with the jump and the new position.
 * It fills its parent, so hosts put it in the same box as the video and it centres on the picture.
 */
@Composable
fun VideoSeekFeedback(state: VideoSeekFeedbackState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
