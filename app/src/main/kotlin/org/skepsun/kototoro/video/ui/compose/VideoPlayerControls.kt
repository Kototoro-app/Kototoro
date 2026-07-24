package org.skepsun.kototoro.video.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

/** Immutable projection of playback state consumed by the Compose player chrome. */
data class VideoPlayerControlState(
    val title: String = "",
    val subtitle: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val controlsVisible: Boolean = true,
    val isScreenLocked: Boolean = false,
    val canSeek: Boolean = false,
    val hasPreviousChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
    val playbackSpeedLabel: String = "1.00x",
    val qualityLabel: String? = null,
    val showChapterMarkers: Boolean = false,
)

/** Events emitted by Compose. The Activity or a ViewModel owns the MPV side effects. */
sealed interface VideoPlayerAction {
    data object NavigateBack : VideoPlayerAction
    data object TogglePlayback : VideoPlayerAction
    data class SeekTo(val positionMs: Long) : VideoPlayerAction
    data class SeekBy(val offsetMs: Long) : VideoPlayerAction
    data object PreviousChapter : VideoPlayerAction
    data object NextChapter : VideoPlayerAction
    data object OpenSubtitleTracks : VideoPlayerAction
    data object OpenChapterSelection : VideoPlayerAction
    data object OpenPlaybackSpeed : VideoPlayerAction
    data object ToggleIntroMarker : VideoPlayerAction
    data object ToggleOutroMarker : VideoPlayerAction
    data object OpenQuality : VideoPlayerAction
    data object OpenSettings : VideoPlayerAction
    data object OpenMore : VideoPlayerAction
    data object ToggleFullscreen : VideoPlayerAction
    data object ToggleScreenLock : VideoPlayerAction
}

/**
 * Compose-only player chrome. Video frames remain outside this component: libmpv renders them
 * through its native Surface while this UI sends declarative [VideoPlayerAction] events.
 */
@Composable
fun VideoPlayerControls(
    state: VideoPlayerControlState,
    onAction: (VideoPlayerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.controlsVisible && !state.isScreenLocked,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            VideoPlayerTopControls(state = state, onAction = onAction)
            Spacer(modifier = Modifier.weight(1f))
            VideoPlayerBottomControls(state = state, onAction = onAction)
        }
    }
}

@Composable
fun VideoPlayerTopControls(
    state: VideoPlayerControlState,
    onAction: (VideoPlayerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.64f),
        contentColor = PlayerControlsForeground,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                contentDescription = "Back",
                onClick = { onAction(VideoPlayerAction.NavigateBack) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PlayerIconButton(
                icon = { Icon(Icons.Filled.Subtitles, contentDescription = null) },
                contentDescription = "Subtitle tracks",
                onClick = { onAction(VideoPlayerAction.OpenSubtitleTracks) },
            )
            PlayerIconButton(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                contentDescription = "Player settings",
                onClick = { onAction(VideoPlayerAction.OpenSettings) },
            )
            PlayerIconButton(
                icon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                contentDescription = "More options",
                onClick = { onAction(VideoPlayerAction.OpenMore) },
            )
        }
    }
}

@Composable
fun VideoPlayerBottomControls(
    state: VideoPlayerControlState,
    onAction: (VideoPlayerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val progress = if (state.durationMs > 0L) {
        state.positionMs.toFloat() / state.durationMs.toFloat()
    } else {
        0f
    }
    val sliderColors = SliderDefaults.colors(
        thumbColor = PlayerControlsForeground,
        activeTrackColor = PlayerControlsForeground,
        inactiveTrackColor = PlayerControlsForeground.copy(alpha = 0.36f),
        disabledThumbColor = PlayerControlsForeground.copy(alpha = 0.38f),
        disabledActiveTrackColor = PlayerControlsForeground.copy(alpha = 0.38f),
        disabledInactiveTrackColor = PlayerControlsForeground.copy(alpha = 0.16f),
    )
    Surface(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.64f),
        contentColor = PlayerControlsForeground,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = tokens.screenHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(
                    icon = {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                    },
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    onClick = { onAction(VideoPlayerAction.TogglePlayback) },
                )
                KototoroSlider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = { onAction(VideoPlayerAction.SeekTo((it * state.durationMs).toLong())) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    enabled = state.canSeek,
                    colors = sliderColors,
                )
                Text(
                    text = formatDuration(state.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(text = "/", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 2.dp))
                Text(text = formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(
                    icon = { Icon(Icons.Filled.SkipPrevious, contentDescription = null) },
                    contentDescription = "Previous chapter",
                    enabled = state.hasPreviousChapter,
                    onClick = { onAction(VideoPlayerAction.PreviousChapter) },
                )
                PlayerIconButton(
                    icon = { Icon(Icons.Filled.SkipNext, contentDescription = null) },
                    contentDescription = "Next chapter",
                    enabled = state.hasNextChapter,
                    onClick = { onAction(VideoPlayerAction.NextChapter) },
                )
                PlayerIconButton(
                    icon = { Icon(Icons.Filled.GridView, contentDescription = null) },
                    contentDescription = "Chapters",
                    onClick = { onAction(VideoPlayerAction.OpenChapterSelection) },
                )
                Spacer(modifier = Modifier.weight(1f))
                PlayerTextButton(state.playbackSpeedLabel) { onAction(VideoPlayerAction.OpenPlaybackSpeed) }
                if (state.showChapterMarkers) {
                    PlayerTextButton("Intro") { onAction(VideoPlayerAction.ToggleIntroMarker) }
                    PlayerTextButton("Outro") { onAction(VideoPlayerAction.ToggleOutroMarker) }
                }
                state.qualityLabel?.let { label ->
                    PlayerTextButton(label) { onAction(VideoPlayerAction.OpenQuality) }
                }
                PlayerIconButton(
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    contentDescription = "Lock controls",
                    onClick = { onAction(VideoPlayerAction.ToggleScreenLock) },
                )
                PlayerIconButton(
                    icon = { Icon(Icons.Filled.Fullscreen, contentDescription = null) },
                    contentDescription = "Toggle fullscreen",
                    onClick = { onAction(VideoPlayerAction.ToggleFullscreen) },
                )
            }
        }
    }
}

private val PlayerControlsForeground = Color.White

@Composable
private fun PlayerIconButton(
    icon: @Composable () -> Unit,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp).semantics { this.contentDescription = contentDescription },
        content = icon,
    )
}

@Composable
private fun PlayerTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(text = text, color = PlayerControlsForeground, style = MaterialTheme.typography.labelLarge)
    }
}

internal fun formatDuration(valueMs: Long): String {
    val seconds = (valueMs.coerceAtLeast(0L) / 1000L).toInt()
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
