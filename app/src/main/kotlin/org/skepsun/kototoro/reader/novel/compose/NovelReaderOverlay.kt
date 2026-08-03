package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.novel.tts.TtsState

@Composable
internal fun NovelReaderOverlay(
	loading: Boolean,
	message: NovelReaderMessage?,
	controlsVisible: Boolean,
	onMessageExpired: (Long) -> Unit,
	ttsVisible: Boolean,
	ttsState: TtsState,
	onTtsPrevious: () -> Unit,
	onTtsPlayPause: () -> Unit,
	onTtsNext: () -> Unit,
	onTtsVoice: () -> Unit,
	onTtsClose: () -> Unit,
) {
	var displayedMessage by remember { mutableStateOf(message) }
	LaunchedEffect(message) {
		if (message != null) displayedMessage = message
	}
	Box(modifier = Modifier.fillMaxSize()) {
		AnimatedVisibility(loading, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
			Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
				Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
					KototoroLoadingIndicator()
					Text(
						stringResource(R.string.loading_),
						style = MaterialTheme.typography.titleMedium,
						modifier = Modifier.padding(top = 10.dp),
					)
				}
			}
		}
		LaunchedEffect(message?.id) {
			val current = message ?: return@LaunchedEffect
			delay(current.durationMillis)
			onMessageExpired(current.id)
		}
		AnimatedVisibility(
			visible = message != null,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = if (controlsVisible) 76.dp else 20.dp),
		) {
			Surface(
				shape = MaterialTheme.shapes.small,
				color = Color.Black.copy(alpha = 0.82f),
				contentColor = Color.White,
			) {
				Text(displayedMessage?.text.orEmpty(), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
			}
		}
		AnimatedVisibility(
			visible = ttsVisible,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
		) {
			Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
				Row(
					horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
				) {
					TtsButton(R.drawable.ic_prev, stringResource(R.string.prev_page), onTtsPrevious)
					TtsButton(
						if (ttsState == TtsState.PLAYING) R.drawable.ic_pause else R.drawable.ic_play,
						stringResource(if (ttsState == TtsState.PLAYING) R.string.pause else R.string.play),
						onTtsPlayPause,
					)
					TtsButton(R.drawable.ic_next, stringResource(R.string.next), onTtsNext)
					TtsButton(R.drawable.ic_voice_input, stringResource(R.string.tts_settings_title), onTtsVoice)
					TtsButton(R.drawable.ic_tts_close, stringResource(R.string.close), onTtsClose)
				}
			}
		}
	}
}

@Composable
private fun TtsButton(icon: Int, description: String, onClick: () -> Unit) {
	IconButton(onClick = onClick) {
		Icon(painterResource(icon), contentDescription = description)
	}
}
