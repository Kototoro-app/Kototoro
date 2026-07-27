package org.skepsun.kototoro.settings.compose

import android.text.Html
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.video.player.MpvConfigManager

@Composable
fun PlaybackSettingsScreen(
    settings: AppSettings,
    onAiSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var mpvConfigDraft by remember { mutableStateOf<String?>(null) }
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

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
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
                    onClick = { mpvConfigDraft = MpvConfigManager.read(context) },
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
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
    mpvConfigDraft?.let { draft ->
        SettingsAlertDialog(
            title = stringResource(R.string.video_mpv_conf),
            onDismissRequest = { mpvConfigDraft = null },
            text = {
                Column {
                    Text(
                        Html.fromHtml(
                            stringResource(R.string.video_mpv_conf_guide),
                            Html.FROM_HTML_MODE_COMPACT,
                        ).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { mpvConfigDraft = it },
                        label = { Text(stringResource(R.string.video_mpv_conf)) },
                        supportingText = { Text(stringResource(R.string.video_mpv_conf_hint)) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        minLines = 8,
                        maxLines = 18,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    )
                }
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        MpvConfigManager.write(context, draft)
                        mpvConfigDraft = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.video_mpv_conf_saved))
                        }
                    },
                )
            },
            dismissButton = {
                Column {
                    if (MpvConfigManager.hasCustomConfig(context)) {
                        SettingsDialogActionButton(
                            text = stringResource(R.string.reset),
                            onClick = {
                                MpvConfigManager.reset(context)
                                mpvConfigDraft = null
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.video_mpv_conf_reset))
                                }
                            },
                        )
                    }
                    SettingsDialogActionButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { mpvConfigDraft = null },
                    )
                }
            },
        )
    }
}
