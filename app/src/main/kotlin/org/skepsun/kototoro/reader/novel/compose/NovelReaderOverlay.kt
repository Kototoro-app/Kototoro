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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.compose.TvDropdownMenuItem
import org.skepsun.kototoro.reader.novel.tts.TtsPlaybackSpeed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.novel.tts.TtsState
import org.skepsun.kototoro.reader.ui.compose.whenReaderAnimationsEnabled

@Composable
internal fun NovelReaderOverlay(
    loading: Boolean,
    message: NovelReaderMessage?,
    controlsVisible: Boolean,
    animationsEnabled: Boolean = true,
    onMessageExpired: (Long) -> Unit,
    ttsVisible: Boolean,
    ttsState: TtsState,
    ttsSpeed: Float,
    onTtsPrevious: () -> Unit,
    onTtsPlayPause: () -> Unit,
    onTtsNext: () -> Unit,
    onTtsSpeedChange: (Float) -> Unit,
    onTtsVoice: () -> Unit,
    onTtsClose: () -> Unit,
) {
    var displayedMessage by remember { mutableStateOf(message) }
    LaunchedEffect(message) {
        if (message != null) displayedMessage = message
    }
    // 朗读条出现时没有任何节点持有焦点：顶栏是收起的，方向键也不会走到这里。
    // 主动把焦点交给播放/暂停键，并逐帧重试，直到节点真正挂上（淡入要先完成放置）。
    val isTv = LocalUiPresentationConfig.current.isTv
    val ttsPlayPauseFocus = remember { FocusRequester() }
    LaunchedEffect(ttsVisible, isTv) {
        if (!isTv || !ttsVisible) return@LaunchedEffect
        repeat(TtsFocusAttempts) {
            withFrameNanos { }
            if (runCatching { ttsPlayPauseFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    // 选项面板是同一层里的兄弟节点、且后组合，会盖住贴底的提示条；提示必须浮在它上面，
    // 否则"按了快捷动作却什么都没发生"在电视上完全无法诊断。
    Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
        AnimatedVisibility(
            visible = loading,
            enter = fadeIn().whenReaderAnimationsEnabled(animationsEnabled),
            exit = fadeOut().whenReaderAnimationsEnabled(animationsEnabled),
            modifier = Modifier.align(Alignment.Center),
        ) {
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
            enter = fadeIn().whenReaderAnimationsEnabled(animationsEnabled),
            exit = fadeOut().whenReaderAnimationsEnabled(animationsEnabled),
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
            enter = fadeIn().whenReaderAnimationsEnabled(animationsEnabled),
            exit = fadeOut().whenReaderAnimationsEnabled(animationsEnabled),
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
                        focusRequester = ttsPlayPauseFocus.takeIf { isTv },
                    )
                    TtsButton(R.drawable.ic_next, stringResource(R.string.next), onTtsNext)
                    TtsSpeedButton(ttsSpeed, onTtsSpeedChange)
                    TtsButton(R.drawable.ic_voice_input, stringResource(R.string.tts_settings_title), onTtsVoice)
                    TtsButton(R.drawable.ic_tts_close, stringResource(R.string.close), onTtsClose)
                }
            }
        }
    }
}

@Composable
private fun TtsButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .tvFocusable(shape = CircleShape, addFocusTarget = false),
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

/** Shows the current TTS speed and opens a menu with the preset speeds. */
@Composable
internal fun TtsSpeedButton(speed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(R.string.tts_speed)
    val isTv = LocalUiPresentationConfig.current.isTv
    // 遥控器只能靠方向键移动焦点，菜单若从第一项起步，用户每次都得从头数到当前倍速。
    // 电视上给每项挂一个 FocusRequester，展开时把焦点直接放到当前值上。
    val optionFocus = remember {
        TtsPlaybackSpeed.OPTIONS.associateWith { FocusRequester() }
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .tvFocusable(shape = MaterialTheme.shapes.small, addFocusTarget = false)
                .semantics { contentDescription = "$title ${TtsPlaybackSpeed.label(speed)}" },
        ) {
            Text(TtsPlaybackSpeed.label(speed), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TtsPlaybackSpeed.OPTIONS.forEach { option ->
                TvDropdownMenuItem(
                    text = {
                        Text(
                            TtsPlaybackSpeed.label(option),
                            color = if (option == speed) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSpeedChange(option)
                    },
                    modifier = if (isTv) {
                        Modifier.focusRequester(optionFocus.getValue(option))
                    } else {
                        Modifier
                    },
                )
            }
        }
        if (isTv) {
            val currentFocus = optionFocus.getValue(
                TtsPlaybackSpeed.OPTIONS.firstOrNull { it == speed } ?: TtsPlaybackSpeed.OPTIONS.first(),
            )
            LaunchedEffect(expanded) {
                if (!expanded) return@LaunchedEffect
                repeat(TtsFocusAttempts) {
                    withFrameNanos { }
                    if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
        }
    }
}

private const val TtsFocusAttempts = 20
