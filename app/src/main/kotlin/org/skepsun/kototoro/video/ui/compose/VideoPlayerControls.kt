package org.skepsun.kototoro.video.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.R
import kotlin.math.roundToInt

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
    val chapterGroupLabel: String? = null,
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
    data class OpenSubtitleTracks(val anchorBounds: IntRect) : VideoPlayerAction
    data class OpenChapterSelection(val anchorBounds: IntRect) : VideoPlayerAction
    data class OpenPlaybackSpeed(val anchorBounds: IntRect) : VideoPlayerAction
    data object ToggleIntroMarker : VideoPlayerAction
    data object ToggleOutroMarker : VideoPlayerAction
    data class OpenQuality(val anchorBounds: IntRect) : VideoPlayerAction
    data class OpenSettings(val anchorBounds: IntRect) : VideoPlayerAction
    data class OpenMore(val anchorBounds: IntRect) : VideoPlayerAction
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
    var settingsAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var subtitleAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                ),
            ),
        color = Color.Transparent,
        contentColor = PlayerControlsForeground,
    ) {
        Row(
            modifier = Modifier
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
                onClick = { onAction(VideoPlayerAction.OpenSubtitleTracks(subtitleAnchorBounds)) },
                modifier = Modifier.onGloballyPositioned {
                    subtitleAnchorBounds = it.boundsInWindowIntRect()
                },
            )
            PlayerIconButton(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                contentDescription = "Player settings",
                onClick = { onAction(VideoPlayerAction.OpenSettings(settingsAnchorBounds)) },
                modifier = Modifier.onGloballyPositioned {
                    settingsAnchorBounds = it.boundsInWindowIntRect()
                },
            )
            PlayerIconButton(
                icon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                contentDescription = "More options",
                onClick = { onAction(VideoPlayerAction.OpenMore(moreAnchorBounds)) },
                modifier = Modifier.onGloballyPositioned {
                    moreAnchorBounds = it.boundsInWindowIntRect()
                },
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
    var chaptersAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var speedAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var qualityAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
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
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                ),
            ),
        color = Color.Transparent,
        contentColor = PlayerControlsForeground,
    ) {
        Column(
            modifier = Modifier
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
                PlayerChapterButton(
                    groupLabel = state.chapterGroupLabel,
                    onClick = { onAction(VideoPlayerAction.OpenChapterSelection(chaptersAnchorBounds)) },
                    modifier = Modifier.onGloballyPositioned {
                        chaptersAnchorBounds = it.boundsInWindowIntRect()
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                PlayerTextButton(
                    text = state.playbackSpeedLabel,
                    onClick = { onAction(VideoPlayerAction.OpenPlaybackSpeed(speedAnchorBounds)) },
                    modifier = Modifier.onGloballyPositioned {
                        speedAnchorBounds = it.boundsInWindowIntRect()
                    },
                )
                if (state.showChapterMarkers) {
                    PlayerTextButton(stringResource(R.string.video_mark_intro)) {
                        onAction(VideoPlayerAction.ToggleIntroMarker)
                    }
                    PlayerTextButton(stringResource(R.string.video_mark_outro)) {
                        onAction(VideoPlayerAction.ToggleOutroMarker)
                    }
                }
                state.qualityLabel?.let { label ->
                    PlayerTextButton(
                        text = label,
                        onClick = { onAction(VideoPlayerAction.OpenQuality(qualityAnchorBounds)) },
                        modifier = Modifier.onGloballyPositioned {
                            qualityAnchorBounds = it.boundsInWindowIntRect()
                        },
                    )
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
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(36.dp).semantics { this.contentDescription = contentDescription },
        content = icon,
    )
}

@Composable
private fun PlayerChapterButton(
    groupLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = groupLabel?.takeIf(String::isNotBlank)
    TextButton(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .widthIn(max = 120.dp)
            .semantics {
                contentDescription = if (label == null) "Chapters" else "Chapters, $label"
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            tint = PlayerControlsForeground,
            modifier = Modifier.size(20.dp),
        )
        label?.let {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = it,
                color = PlayerControlsForeground,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun LayoutCoordinates.boundsInWindowIntRect(): IntRect {
    val position = positionInWindow()
    val topLeft = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    return IntRect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + size.width,
        bottom = topLeft.y + size.height,
    )
}

@Composable
private fun PlayerTextButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
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
