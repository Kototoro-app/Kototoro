package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

data class TtsSettingsUiState(
    val enabled: Boolean,
    val engineType: String,
    val systemVoice: String,
    val systemVoiceOptions: List<SettingsChoiceOption<String>>,
    val systemVoiceSummary: String?,
    val legadoVoice: String,
    val legadoVoiceOptions: List<SettingsChoiceOption<String>>,
    val legadoVoiceSummary: String?,
    val legadoConfigCount: Int,
    val isTestRunning: Boolean,
)

@Composable
fun TtsSettingsScreen(
    state: TtsSettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onEngineTypeChange: (String) -> Unit,
    onSystemVoiceChange: (String) -> Unit,
    onLegadoVoiceChange: (String) -> Unit,
    onTestClick: () -> Unit,
    onImportClipboardClick: () -> Unit,
    onImportUrlClick: () -> Unit,
    onManageSourcesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSystemEngine = state.engineType == "SYSTEM"

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPreferenceGroup(title = stringResource(R.string.reader_translation_section_general)) {
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.tts_enable),
                        iconRes = R.drawable.ic_voice_input,
                        checked = state.enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.tts_engine_type),
                        iconRes = R.drawable.ic_audiotrack,
                        value = state.engineType,
                        options = listOf(
                            SettingsChoiceOption("SYSTEM", stringResource(R.string.tts_engine_system)),
                            SettingsChoiceOption("LEGADO", stringResource(R.string.tts_engine_legado)),
                        ),
                        enabled = state.enabled,
                        onValueChange = onEngineTypeChange,
                    )
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.tts_test),
                        iconRes = R.drawable.ic_plug,
                        summary = stringResource(
                            if (state.isTestRunning) R.string.tts_test_running_summary
                            else R.string.tts_test_summary,
                        ),
                        enabled = state.enabled && !state.isTestRunning,
                        showChevron = false,
                        onClick = onTestClick,
                    )
                }
            }

            if (isSystemEngine) {
                SettingsPreferenceGroup(title = stringResource(R.string.tts_system_configuration)) {
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.tts_system_voice),
                            iconRes = R.drawable.ic_voice_input,
                            value = state.systemVoice,
                            options = state.systemVoiceOptions,
                            summary = state.systemVoiceSummary,
                            enabled = state.enabled && state.systemVoiceOptions.isNotEmpty(),
                            onValueChange = onSystemVoiceChange,
                        )
                    }
                }
            } else {
                SettingsPreferenceGroup(title = stringResource(R.string.tts_legado_configuration)) {
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.tts_legado_voice),
                            iconRes = R.drawable.ic_voice_input,
                            value = state.legadoVoice,
                            options = state.legadoVoiceOptions,
                            summary = state.legadoVoiceSummary,
                            enabled = state.enabled && state.legadoVoiceOptions.isNotEmpty(),
                            onValueChange = onLegadoVoiceChange,
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.tts_legado_import_clipboard),
                            iconRes = R.drawable.ic_import,
                            summary = stringResource(R.string.tts_legado_import_clipboard_summary),
                            enabled = state.enabled,
                            showChevron = false,
                            onClick = onImportClipboardClick,
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.tts_legado_import_url),
                            iconRes = R.drawable.ic_web,
                            summary = stringResource(R.string.tts_legado_import_url_summary),
                            enabled = state.enabled,
                            showChevron = false,
                            onClick = onImportUrlClick,
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.tts_legado_manage_sources),
                            iconRes = R.drawable.ic_services,
                            summary = stringResource(
                                if (state.legadoConfigCount > 0) {
                                    R.string.tts_legado_manage_sources_summary_count
                                } else {
                                    R.string.tts_legado_manage_sources_summary_empty
                                },
                                state.legadoConfigCount,
                            ),
                            enabled = state.enabled && state.legadoConfigCount > 0,
                            showChevron = false,
                            onClick = onManageSourcesClick,
                        )
                    }
                }
            }
        }
    }
}
