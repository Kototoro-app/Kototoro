package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun PlaybackSettingsScreen(
    settings: AppSettings,
    onAiSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val background by settings.observeAsState(AppSettings.KEY_VIDEO_BACKGROUND) { videoBackground }
    val controlsAlpha by settings.observeAsState(AppSettings.KEY_VIDEO_CONTROLS_ALPHA) { videoControlsAlpha }
    val gradientAlpha by settings.observeAsState(AppSettings.KEY_VIDEO_GRADIENT_ALPHA) { videoGradientAlpha }

    val readerBackgroundNames = org.skepsun.kototoro.core.prefs.ReaderBackground.entries.map { it.name }

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
                    title = stringResource(R.string.video_background),
                    options = backgroundOptions,
                    value = background.name,
                    onValueChange = { settings.videoBackground = org.skepsun.kototoro.core.prefs.ReaderBackground.valueOf(it) },
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
}
