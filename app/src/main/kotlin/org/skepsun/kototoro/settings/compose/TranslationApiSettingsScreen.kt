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

@Composable
fun TranslationApiSettingsScreen(
    settings: AppSettings,
    onFetchModelsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs

    val presetNames = stringArrayResource(R.array.values_reader_translation_api_provider_presets).toList()

    val currentMode = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_MODE) { settings.readerTranslationMode }.value

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
            val summaryRes = when (currentMode) {
                ReaderTranslationMode.LOCAL_ONLY -> R.string.ai_api_usage_summary_local_only
                ReaderTranslationMode.API_ONLY -> R.string.ai_api_usage_summary_api_only
                ReaderTranslationMode.LOCAL_FIRST -> R.string.ai_api_usage_summary_local_first
            }

            SettingsPreferenceSection(
                title = stringResource(R.string.ai_api_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsActionPreference(
                    title = stringResource(R.string.reader_translation_mode),
                    summary = stringResource(summaryRes),
                    onClick = {},
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.reader_translation_api_provider_preset),
                    options = stringArrayResource(R.array.reader_translation_api_provider_presets).mapIndexed { index, label ->
                        SettingsChoiceOption(presetNames[index], label)
                    },
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET) {
                        prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET, "CUSTOM") ?: "CUSTOM"
                    }.value,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET, it) } },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_endpoint),
                    summary = stringResource(R.string.reader_translation_api_endpoint_summary),
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT) {
                        prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT, "") ?: ""
                    }.value,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT, it) } },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_key),
                    summary = stringResource(R.string.reader_translation_api_key_summary),
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_KEY) {
                        prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_KEY, "") ?: ""
                    }.value,
                    isPassword = true,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_KEY, it) } },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_model),
                    summary = stringResource(R.string.reader_translation_api_model_summary),
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_MODEL) {
                        prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
                    }.value,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_MODEL, it) } },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_custom_headers),
                    summary = stringResource(R.string.reader_translation_api_custom_headers_summary),
                    value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_CUSTOM_HEADERS) {
                        prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_CUSTOM_HEADERS, "") ?: ""
                    }.value,
                    onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_CUSTOM_HEADERS, it) } },
                )
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = stringResource(R.string.reader_translation_api_models_fetch),
                    summary = stringResource(R.string.reader_translation_api_models_fetch_summary),
                    onClick = onFetchModelsClick,
                )
            }
        }
    }
}
