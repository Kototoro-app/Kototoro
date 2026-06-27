package org.skepsun.kototoro.settings.compose

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
        ) {
            SettingsPreferenceSection(title = stringResource(R.string.reader_translation_section_general)) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.tts_enable),
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                )
                SettingsChoicePreference(
                    title = stringResource(R.string.tts_engine_type),
                    value = state.engineType,
                    options = listOf(
                        SettingsChoiceOption("SYSTEM", stringResource(R.string.tts_engine_system)),
                        SettingsChoiceOption("LEGADO", stringResource(R.string.tts_engine_legado)),
                    ),
                    enabled = state.enabled,
                    onValueChange = onEngineTypeChange,
                )
                SettingsActionPreference(
                    title = stringResource(R.string.tts_test),
                    summary = stringResource(
                        if (state.isTestRunning) R.string.tts_test_running_summary
                        else R.string.tts_test_summary,
                    ),
                    enabled = state.enabled && !state.isTestRunning,
                    showChevron = false,
                    onClick = onTestClick,
                )
            }

            if (isSystemEngine) {
                SettingsPreferenceSection(title = stringResource(R.string.tts_system_configuration)) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.tts_system_voice),
                        value = state.systemVoice,
                        options = state.systemVoiceOptions,
                        summary = state.systemVoiceSummary,
                        enabled = state.enabled && state.systemVoiceOptions.isNotEmpty(),
                        onValueChange = onSystemVoiceChange,
                    )
                }
            } else {
                SettingsPreferenceSection(title = stringResource(R.string.tts_legado_configuration)) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.tts_legado_voice),
                        value = state.legadoVoice,
                        options = state.legadoVoiceOptions,
                        summary = state.legadoVoiceSummary,
                        enabled = state.enabled && state.legadoVoiceOptions.isNotEmpty(),
                        onValueChange = onLegadoVoiceChange,
                    )
                    SettingsActionPreference(
                        title = stringResource(R.string.tts_legado_import_clipboard),
                        summary = stringResource(R.string.tts_legado_import_clipboard_summary),
                        enabled = state.enabled,
                        showChevron = false,
                        onClick = onImportClipboardClick,
                    )
                    SettingsActionPreference(
                        title = stringResource(R.string.tts_legado_import_url),
                        summary = stringResource(R.string.tts_legado_import_url_summary),
                        enabled = state.enabled,
                        showChevron = false,
                        onClick = onImportUrlClick,
                    )
                    SettingsActionPreference(
                        title = stringResource(R.string.tts_legado_manage_sources),
                        summary = stringResource(
                            if (state.legadoConfigCount > 0) R.string.tts_legado_manage_sources_summary_count
                            else R.string.tts_legado_manage_sources_summary_empty,
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
