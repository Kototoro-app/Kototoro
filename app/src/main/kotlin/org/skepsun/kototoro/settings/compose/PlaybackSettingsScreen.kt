package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsAction
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsContent
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsRowIcons
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsUiState
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice

/** Android observes/persists preferences; the page and its choice editors are shared with desktop. */
@Composable
fun PlaybackSettingsScreen(
    settings: AppSettings,
    onAiSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by settings.observeAsState(AppSettings.KEY_VIDEO_BACKGROUND) { videoBackground }
    val decoderMode by settings.observeAsState(AppSettings.KEY_VIDEO_DECODER_MODE) { videoDecoderMode }
    val controlsAlpha by settings.observeAsState(AppSettings.KEY_VIDEO_CONTROLS_ALPHA) { videoControlsAlpha }
    val gradientAlpha by settings.observeAsState(AppSettings.KEY_VIDEO_GRADIENT_ALPHA) { videoGradientAlpha }

    // The array order matches ReaderBackground/VideoDecoderMode; the values stay the persisted enum names.
    val backgroundOptions = stringArrayResource(R.array.video_backgrounds).mapIndexed { index, label ->
        PreferenceChoice(ReaderBackground.entries[index].name, label)
    }
    val decoderOptions = stringArrayResource(R.array.video_decoder_modes).mapIndexed { index, label ->
        PreferenceChoice(VideoDecoderMode.entries[index].name, label)
    }

    PlaybackSettingsContent(
        state = PlaybackSettingsUiState(
            background = background.name,
            decoderMode = decoderMode.name,
            controlsAlpha = (controlsAlpha * 100f).toInt(),
            gradientAlpha = (gradientAlpha * 100f).toInt(),
        ),
        backgroundOptions = backgroundOptions,
        decoderOptions = decoderOptions,
        onBackgroundChange = { settings.videoBackground = ReaderBackground.valueOf(it) },
        onDecoderChange = { settings.videoDecoderMode = VideoDecoderMode.valueOf(it) },
        onControlsAlphaChange = { settings.videoControlsAlpha = it / 100f },
        onGradientAlphaChange = { settings.videoGradientAlpha = it / 100f },
        modifier = modifier.fillMaxSize()
            .padding(top = settingsContentTopInset())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        enabled = true,
        icons = PlaybackSettingsRowIcons(
            background = painterResource(R.drawable.ic_images),
            decoderMode = painterResource(R.drawable.ic_services),
            controlsAlpha = painterResource(R.drawable.ic_eye),
            gradientAlpha = painterResource(R.drawable.ic_bar_chart),
            aiSettings = painterResource(R.drawable.ic_auto_fix),
        ),
        aiSettings = PlaybackSettingsAction(
            title = stringResource(R.string.ai_settings),
            summary = stringResource(R.string.ai_settings_entry_summary),
            onClick = onAiSettingsClick,
        ),
        renderDialog = { AndroidPreferenceDialog(it) },
    )
}
