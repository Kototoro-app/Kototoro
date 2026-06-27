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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionShader

@Composable
fun AIVideoEnhancementSettingsScreen(
    settings: AppSettings,
    onAdvancedSettingsClick: () -> Unit,
) {
    val modeEntries = VideoSuperResolutionMode.entries.map {
        SettingsChoiceOption(it.name, it.name)
    }
    val shaderEntries = VideoSuperResolutionShader.entries.map {
        SettingsChoiceOption(it.name, it.name)
    }

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
                title = stringResource(R.string.ai_video_enhancement_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsChoicePreference(
                    title = stringResource(R.string.video_super_resolution_mode),
                    value = settings.prefs.getString(AppSettings.KEY_VIDEO_SUPER_RES_MODE, VideoSuperResolutionMode.BALANCED.name)
                        ?: VideoSuperResolutionMode.BALANCED.name,
                    options = modeEntries,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_VIDEO_SUPER_RES_MODE, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.video_super_resolution_submode_quality),
                    value = settings.prefs.getString(AppSettings.KEY_VIDEO_SUPER_RES_QUALITY_SHADER, VideoSuperResolutionShader.MODE_A.name)
                        ?: VideoSuperResolutionShader.MODE_A.name,
                    options = shaderEntries,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_VIDEO_SUPER_RES_QUALITY_SHADER, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.video_super_resolution_submode_balanced),
                    value = settings.prefs.getString(AppSettings.KEY_VIDEO_SUPER_RES_BALANCED_SHADER, VideoSuperResolutionShader.MODE_B.name)
                        ?: VideoSuperResolutionShader.MODE_B.name,
                    options = shaderEntries,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_VIDEO_SUPER_RES_BALANCED_SHADER, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.video_super_resolution_submode_performance),
                    value = settings.prefs.getString(AppSettings.KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER, VideoSuperResolutionShader.MODE_C.name)
                        ?: VideoSuperResolutionShader.MODE_C.name,
                    options = shaderEntries,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = stringResource(R.string.video_super_resolution_advanced_settings),
                    summary = stringResource(R.string.video_super_resolution_advanced_shader),
                    onClick = onAdvancedSettingsClick,
                )
            }
        }
    }
}
