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
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun TranslationSettingsScreen(
    settings: AppSettings,
	onOcrModeChange: (ReaderOcrMode) -> Unit,
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
	val prefs = settings.prefs

	val modeNames = stringArrayResource(R.array.values_reader_translation_modes).toList()
	val sourceLangNames = stringArrayResource(R.array.values_reader_translation_source_languages).toList()
	val targetLangNames = stringArrayResource(R.array.values_reader_translation_target_languages).toList()
	val renderStyleNames = stringArrayResource(R.array.values_reader_translation_render_styles).toList()
	val currentMode = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_MODE) {
		settings.readerTranslationMode
	}.value
	val currentOcrMode = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_OCR_MODE) {
		settings.readerTranslationOcrMode
	}.value

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
				SettingsChoicePreference(
					title = stringResource(R.string.reader_translation_mode),
					options = stringArrayResource(R.array.reader_translation_modes).mapIndexed { index, label ->
						SettingsChoiceOption(modeNames[index], label)
					},
					value = currentMode.name,
					onSettingsClick = onOpenApiSettings.takeIf { currentMode == ReaderTranslationMode.API_ONLY },
					settingsContentDescription = stringResource(R.string.reader_translation_open_api_settings),
					onValueChange = { prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_MODE, it) } },
				)

				SettingsChoicePreference(
					title = stringResource(R.string.reader_translation_ocr_mode),
					options = listOf(
						SettingsChoiceOption(ReaderOcrMode.BASIC, stringResource(R.string.reader_translation_ocr_mode_basic)),
						SettingsChoiceOption(ReaderOcrMode.ADVANCED, stringResource(R.string.reader_translation_ocr_mode_advanced)),
					),
					value = currentOcrMode,
					onSettingsClick = onOpenOcrModels.takeIf { currentOcrMode == ReaderOcrMode.ADVANCED },
					settingsContentDescription = stringResource(R.string.reader_translation_ocr_advanced_settings),
					onValueChange = onOcrModeChange,
				)

				SettingsChoicePreference(
					title = stringResource(R.string.reader_translation_render_style),
					options = stringArrayResource(R.array.reader_translation_render_styles).mapIndexed { index, label ->
						SettingsChoiceOption(renderStyleNames[index], label)
					},
					value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_RENDER_STYLE) {
						settings.readerTranslationRenderStyle
					}.value,
					onValueChange = {
						prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_RENDER_STYLE, it) }
					},
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

				SettingsSwitchPreference(
					title = stringResource(R.string.reader_translation_debug_logs),
					summary = stringResource(R.string.reader_translation_debug_logs_summary),
					checked = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_DEBUG_LOGS) {
						settings.isReaderTranslationDebugLogsEnabled
					}.value,
					onCheckedChange = { prefs.edit { putBoolean(AppSettings.KEY_READER_TRANSLATION_DEBUG_LOGS, it) } },
				)
			}

        }
    }
}
