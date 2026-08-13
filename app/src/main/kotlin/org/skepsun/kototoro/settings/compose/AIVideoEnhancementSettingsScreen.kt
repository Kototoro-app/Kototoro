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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.Anime4KPreset
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.VideoEnhancementAlgorithm
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun AIVideoEnhancementSettingsScreen(settings: AppSettings) {
    val algorithm by settings.observeAsState(AppSettings.KEY_VIDEO_ENHANCEMENT_ALGORITHM) {
        videoEnhancementAlgorithm
    }
    val preset by settings.observeAsState(AppSettings.KEY_VIDEO_ANIME4K_PRESET) { videoAnime4KPreset }
    val sharpness by settings.observeAsState(AppSettings.KEY_VIDEO_FSR_SHARPNESS) { videoFsrSharpness }
    val remember by settings.observeAsState(AppSettings.KEY_VIDEO_ENHANCEMENT_REMEMBER) {
        videoEnhancementRememberAcrossVideos
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_video_enhancement_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.video_enhancement_algorithm),
                        iconRes = R.drawable.ic_auto_fix,
                        value = algorithm.name,
                        options = listOf(
                            SettingsChoiceOption(
                                VideoEnhancementAlgorithm.ANIME4K.name,
                                stringResource(R.string.video_enhancement_algorithm_anime4k),
                            ),
                            SettingsChoiceOption(
                                VideoEnhancementAlgorithm.FSR_1_0.name,
                                stringResource(R.string.video_enhancement_algorithm_fsr),
                            ),
                        ),
                        onValueChange = {
                            settings.videoEnhancementAlgorithm = VideoEnhancementAlgorithm.valueOf(it)
                        },
                    )
                }
                if (algorithm == VideoEnhancementAlgorithm.ANIME4K) {
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.video_enhancement_anime4k_preset),
                            iconRes = R.drawable.ic_timelapse,
                            value = preset.name,
                            options = listOf(
                                SettingsChoiceOption(
                                    Anime4KPreset.FAST.name,
                                    stringResource(R.string.video_enhancement_anime4k_fast),
                                ),
                                SettingsChoiceOption(
                                    Anime4KPreset.QUALITY.name,
                                    stringResource(R.string.video_enhancement_anime4k_quality),
                                ),
                            ),
                            onValueChange = { settings.videoAnime4KPreset = Anime4KPreset.valueOf(it) },
                        )
                    }
                } else {
                    item {
                        SettingsSliderPreference(
                            title = stringResource(R.string.video_enhancement_fsr_sharpness_search),
                            iconRes = R.drawable.ic_zoom_in,
                            summary = "${(sharpness * 100).toInt()}%",
                            value = (sharpness * 100).toInt(),
                            valueRange = 0..100,
                            step = 10,
                            valueText = { "$it%" },
                            onValueChange = { settings.videoFsrSharpness = it / 100f },
                        )
                    }
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.video_enhancement_remember),
                        iconRes = R.drawable.ic_save_ok,
                        summary = stringResource(R.string.video_enhancement_power_warning),
                        checked = remember,
                        onCheckedChange = { enabled ->
                            settings.videoEnhancementRememberAcrossVideos = enabled
                            if (!enabled) settings.videoEnhancementRememberedEnabled = false
                        },
                    )
                }
            }
        }
    }
}
