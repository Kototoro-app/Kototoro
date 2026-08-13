package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
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

@Composable
fun AISettingsScreen(
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onOpenImageEnhancementSettings: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenVideoEnhancementSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_section_core),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.reader_translation_manage_ocr_models),
                        summary = stringResource(R.string.reader_translation_manage_ocr_models_summary),
                        iconRes = R.drawable.ic_script,
                        onClick = onOpenOcrModels,
                    )
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.ai_api_settings),
                        summary = stringResource(R.string.ai_api_settings_summary),
                        iconRes = R.drawable.ic_key,
                        onClick = onOpenApiSettings,
                    )
                }
            }

            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_section_translation),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.translation_settings),
                        summary = stringResource(R.string.reader_translation_settings_entry_summary),
                        iconRes = R.drawable.ic_translate,
                        onClick = onOpenTranslationSettings,
                    )
                }
            }

            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_section_image),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.ai_image_enhancement_settings),
                        summary = stringResource(R.string.ai_image_enhancement_summary),
                        iconRes = R.drawable.ic_zoom_in,
                        onClick = onOpenImageEnhancementSettings,
                    )
                }
            }

            SettingsPreferenceGroup(
                title = stringResource(R.string.tts_section_voice_subtitle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.tts_settings_title),
                        summary = stringResource(R.string.tts_settings_summary),
                        iconRes = R.drawable.ic_voice_input,
                        onClick = onOpenTtsSettings,
                    )
                }
            }

            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_section_video),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.ai_video_enhancement_settings),
                        summary = stringResource(R.string.ai_video_enhancement_summary),
                        iconRes = R.drawable.ic_content_video,
                        onClick = onOpenVideoEnhancementSettings,
                    )
                }
            }
        }
    }
}
