package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun PlaybackSettingsScreen(
    settings: AppSettings,
    onMpvConfClick: () -> Unit,
    onAiSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decoderMode by settings.observeAsState(AppSettings.KEY_VIDEO_DECODER_MODE) { videoDecoderMode }
    val rendererMode by settings.observeAsState(AppSettings.KEY_VIDEO_RENDERER_MODE) { videoRendererMode }
    val background by settings.observeAsState(AppSettings.KEY_VIDEO_BACKGROUND) { videoBackground }
    val controlsAlpha by settings.observeAsState(AppSettings.KEY_VIDEO_CONTROLS_ALPHA) { videoControlsAlpha }
    val gradientAlpha by settings.observeAsState(AppSettings.KEY_VIDEO_GRADIENT_ALPHA) { videoGradientAlpha }

    val decoderModeNames = org.skepsun.kototoro.core.prefs.VideoDecoderMode.entries.map { it.name }
    val rendererModeNames = org.skepsun.kototoro.core.prefs.VideoRendererMode.entries.map { it.name }
    val readerBackgroundNames = org.skepsun.kototoro.core.prefs.ReaderBackground.entries.map { it.name }

    val decoderModeOptions = stringArrayResource(R.array.video_decoder_modes).mapIndexed { index, label ->
        SettingsChoiceOption(decoderModeNames[index], label)
    }
    val rendererModeOptions = stringArrayResource(R.array.video_renderer_modes).mapIndexed { index, label ->
        SettingsChoiceOption(rendererModeNames[index], label)
    }
    val backgroundOptions = stringArrayResource(R.array.video_backgrounds).mapIndexed { index, label ->
        SettingsChoiceOption(readerBackgroundNames[index], label)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceSection(
                title = stringResource(R.string.playback_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsChoicePreference(
                    title = stringResource(R.string.video_decoder_mode),
                    options = decoderModeOptions,
                    value = decoderMode.name,
                    onValueChange = { settings.videoDecoderMode = org.skepsun.kototoro.core.prefs.VideoDecoderMode.valueOf(it) },
                )

                SettingsChoicePreference(
                    title = stringResource(R.string.video_renderer_mode),
                    options = rendererModeOptions,
                    value = rendererMode.name,
                    onValueChange = { settings.videoRendererMode = org.skepsun.kototoro.core.prefs.VideoRendererMode.valueOf(it) },
                )

                SettingsChoicePreference(
                    title = stringResource(R.string.video_background),
                    options = backgroundOptions,
                    value = background.name,
                    onValueChange = { settings.videoBackground = org.skepsun.kototoro.core.prefs.ReaderBackground.valueOf(it) },
                )

                SettingsActionPreference(
                    title = stringResource(R.string.video_mpv_conf),
                    summary = stringResource(R.string.video_mpv_conf_hint),
                    onClick = onMpvConfClick,
                )

                SettingsActionPreference(
                    title = stringResource(R.string.ai_settings),
                    summary = stringResource(R.string.ai_settings_entry_summary),
                    onClick = onAiSettingsClick,
                )

                SettingsSliderPreference(
                    title = stringResource(R.string.video_controls_alpha),
                    summary = "${(controlsAlpha * 100).toInt()}%",
                    value = (controlsAlpha * 100f).toInt(),
                    valueRange = 30..100,
                    step = 1,
                    valueText = { "$it%" },
                    onValueChange = { settings.videoControlsAlpha = it / 100f },
                )

                SettingsSliderPreference(
                    title = stringResource(R.string.video_gradient_alpha),
                    summary = "${(gradientAlpha * 100).toInt()}%",
                    value = (gradientAlpha * 100f).toInt(),
                    valueRange = 0..100,
                    step = 1,
                    valueText = { "$it%" },
                    onValueChange = { settings.videoGradientAlpha = it / 100f },
                )
            }
        }
    }
}
