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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode

data class TranslationModelOption(val id: String, val title: String)

@Composable
fun TranslationSettingsScreen(
    settings: AppSettings,
    onnxModels: List<SettingsChoiceOption<String>>,
    paddleDetModels: List<SettingsChoiceOption<String>>,
    paddleOfficialModels: List<SettingsChoiceOption<String>>,
    onnxBubbleModels: List<SettingsChoiceOption<String>>,
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenE2eApiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs

    val modeNames = stringArrayResource(R.array.values_reader_translation_modes).toList()
    val pipelineModeNames = stringArrayResource(R.array.values_reader_translation_pipeline_modes).toList()
    val sourceLangNames = stringArrayResource(R.array.values_reader_translation_source_languages).toList()
    val targetLangNames = stringArrayResource(R.array.values_reader_translation_target_languages).toList()
    val ocrPipelineNames = stringArrayResource(R.array.values_reader_translation_ocr_pipeline_strategies).toList()
    val tuningLevelNames = stringArrayResource(R.array.values_reader_translation_tuning_levels).toList()
    val renderStyleNames = stringArrayResource(R.array.values_reader_translation_render_styles).toList()

    val currentMode = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_MODE) { settings.readerTranslationMode }.value
    val currentPipelineMode = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_PIPELINE_MODE) { settings.readerTranslationPipelineMode }.value
    val isBubbleDetectorEnabled = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_BUBBLE_DETECTOR_ENABLED) { settings.isReaderTranslationBubbleDetectorEnabled }.value

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
                title = stringResource(R.string.reader_translation_section_general),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.reader_translation_debug_logs),
                    summary = stringResource(R.string.reader_translation_debug_logs_summary),
                    checked = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_DEBUG_LOGS) { prefs.getBoolean(AppSettings.KEY_READER_TRANSLATION_DEBUG_LOGS, false) }.value,
                    onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_TRANSLATION_DEBUG_LOGS, it) } }
                )
                SettingsSwitchPreference(
                    title = stringResource(R.string.reader_translation_quality_filter_enabled),
                    summary = stringResource(R.string.reader_translation_quality_filter_enabled_summary),
                    checked = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_QUALITY_FILTER_ENABLED) { prefs.getBoolean(AppSettings.KEY_READER_TRANSLATION_QUALITY_FILTER_ENABLED, true) }.value,
                    onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_TRANSLATION_QUALITY_FILTER_ENABLED, it) } }
                )
                
                if (currentPipelineMode == ReaderTranslationPipelineMode.TWO_STAGE) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_mode),
                        options = stringArrayResource(R.array.reader_translation_modes).mapIndexed { index, label ->
                            SettingsChoiceOption(modeNames[index], label)
                        },
                        value = currentMode.name,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_MODE, it) } }
                    )
                }

                SettingsChoicePreference(
                    title = stringResource(R.string.reader_translation_pipeline_mode),
                    options = stringArrayResource(R.array.reader_translation_pipeline_modes).mapIndexed { index, label ->
                        SettingsChoiceOption(pipelineModeNames[index], label)
                    },
                    value = currentPipelineMode.name,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_PIPELINE_MODE, it) } }
                )

                SettingsChoicePreference(
                    title = stringResource(R.string.reader_translation_source_lang),
                    options = stringArrayResource(R.array.reader_translation_source_languages).mapIndexed { index, label ->
                        SettingsChoiceOption(sourceLangNames[index], label)
                    },
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_SOURCE_LANG) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_SOURCE_LANG, "auto") ?: "auto" }.value,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_SOURCE_LANG, it) } }
                )

                SettingsChoicePreference(
                    title = stringResource(R.string.reader_translation_target_lang),
                    options = stringArrayResource(R.array.reader_translation_target_languages).mapIndexed { index, label ->
                        SettingsChoiceOption(targetLangNames[index], label)
                    },
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_TARGET_LANG) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_TARGET_LANG, "zh") ?: "zh" }.value,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_TARGET_LANG, it) } }
                )

                if (currentPipelineMode == ReaderTranslationPipelineMode.TWO_STAGE && currentMode != ReaderTranslationMode.API_ONLY) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_onnx_model_selection),
                        options = onnxModels,
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_ONNX_MODEL_ID) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_ONNX_MODEL_ID, "") ?: "" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_ONNX_MODEL_ID, it) } }
                    )
                }

                val showApi = currentMode != ReaderTranslationMode.LOCAL_ONLY && currentPipelineMode != ReaderTranslationPipelineMode.END_TO_END_API
                val showE2eApi = currentPipelineMode == ReaderTranslationPipelineMode.END_TO_END_API

                if (showApi) {
                    SettingsActionPreference(
                        title = stringResource(R.string.reader_translation_open_api_settings),
                        summary = stringResource(R.string.reader_translation_open_api_settings_summary),
                        onClick = onOpenApiSettings
                    )
                }
                if (showE2eApi) {
                    SettingsActionPreference(
                        title = stringResource(R.string.reader_translation_e2e_api_settings_title),
                        summary = stringResource(R.string.reader_translation_e2e_api_settings_summary),
                        onClick = onOpenE2eApiSettings
                    )
                }
            }

            if (currentPipelineMode == ReaderTranslationPipelineMode.TWO_STAGE) {
                SettingsPreferenceSection(
                    title = stringResource(R.string.reader_translation_section_ocr),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SettingsActionPreference(
                        title = stringResource(R.string.reader_translation_manage_ocr_models),
                        summary = stringResource(R.string.reader_translation_manage_ocr_models_summary),
                        onClick = onOpenOcrModels
                    )

                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_ocr_det_model_selection),
                        options = paddleDetModels,
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID, "MLKIT") ?: "MLKIT" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID, it) } }
                    )

                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_ocr_recognizer_model_selection),
                        options = paddleOfficialModels,
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID, "AUTO") ?: "AUTO" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID, it) } }
                    )

                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_ocr_pipeline_strategy),
                        options = stringArrayResource(R.array.reader_translation_ocr_pipeline_strategies).mapIndexed { index, label ->
                            SettingsChoiceOption(ocrPipelineNames[index], label)
                        },
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_OCR_PIPELINE_STRATEGY) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_OCR_PIPELINE_STRATEGY, "HYBRID") ?: "HYBRID" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_OCR_PIPELINE_STRATEGY, it) } }
                    )
                }

                SettingsPreferenceSection(
                    title = stringResource(R.string.reader_translation_section_bubble),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isBubbleDetectorEnabled) {
                        SettingsChoicePreference(
                            title = stringResource(R.string.reader_translation_onnx_bubble_model_selection),
                            options = onnxBubbleModels,
                            value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID, "AUTO") ?: "AUTO" }.value,
                            onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID, it) } }
                        )

                        val rawModelId = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID) { settings.readerTranslationBubbleDetectorModelId }.value
                        val isDetr = rawModelId.contains("detr", ignoreCase = true) || rawModelId.contains("transformers", ignoreCase = true)
                        SettingsSliderPreference(
                            title = stringResource(R.string.reader_translation_bubble_detector_nms),
                            value = (settings.observeAsState("dummy_nms_ticker") { settings.getBubbleDetectorNms(rawModelId, isDetr) }.value * 100).toInt(),
                            valueRange = 0..100,
                            step = 1,
                            valueText = { it.toString() },
                            onValueChange = { settings.setBubbleDetectorNms(rawModelId, it / 100f) }
                        )
                    }

                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_bubble_grouping_tuning),
                        options = stringArrayResource(R.array.reader_translation_tuning_levels).mapIndexed { index, label ->
                            SettingsChoiceOption(tuningLevelNames[index], label)
                        },
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_TUNING) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_TUNING, "BALANCED") ?: "BALANCED" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_TUNING, it) } }
                    )

                    SettingsSwitchPreference(
                        title = stringResource(R.string.reader_translation_bubble_detector_enabled),
                        summary = stringResource(R.string.reader_translation_bubble_detector_enabled_summary),
                        checked = isBubbleDetectorEnabled,
                        onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_TRANSLATION_BUBBLE_DETECTOR_ENABLED, it) } }
                    )

                    SettingsSwitchPreference(
                        title = stringResource(
                            if (isBubbleDetectorEnabled) R.string.reader_translation_bubble_grouping_enabled
                            else R.string.reader_translation_bubble_grouping_enabled_no_detector
                        ),
                        summary = stringResource(
                            if (isBubbleDetectorEnabled) R.string.reader_translation_bubble_grouping_enabled_summary
                            else R.string.reader_translation_bubble_grouping_enabled_summary_no_detector
                        ),
                        checked = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_ENABLED) { prefs.getBoolean(AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_ENABLED, true) }.value,
                        onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_ENABLED, it) } }
                    )

                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_overlay_compactness),
                        options = stringArrayResource(R.array.reader_translation_tuning_levels).mapIndexed { index, label ->
                            SettingsChoiceOption(tuningLevelNames[index], label)
                        },
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_OVERLAY_COMPACTNESS) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_OVERLAY_COMPACTNESS, "BALANCED") ?: "BALANCED" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_OVERLAY_COMPACTNESS, it) } }
                    )

                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_render_style),
                        options = stringArrayResource(R.array.reader_translation_render_styles).mapIndexed { index, label ->
                            SettingsChoiceOption(renderStyleNames[index], label)
                        },
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_RENDER_STYLE) { prefs.getString(AppSettings.KEY_READER_TRANSLATION_RENDER_STYLE, "REPLACE") ?: "REPLACE" }.value,
                        onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_RENDER_STYLE, it) } }
                    )
                }
            }
        }
    }
}
