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
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun TranslationE2ESettingsScreen(
    settings: AppSettings,
    onFetchModels: () -> Unit,
) {
    val prefs = settings.prefs
    val providerPreset = settings.observeAsState(AppSettings.KEY_READER_E2E_API_PROVIDER_PRESET) {
        prefs.getString(AppSettings.KEY_READER_E2E_API_PROVIDER_PRESET, "GEMINI") ?: "GEMINI"
    }.value
    val endpoint = settings.observeAsState(AppSettings.KEY_READER_E2E_API_ENDPOINT) {
        settings.readerE2eApiEndpoint
    }.value
    val apiKey = settings.observeAsState(AppSettings.KEY_READER_E2E_API_KEY) {
        settings.readerE2eApiKey
    }.value
    val model = settings.observeAsState(AppSettings.KEY_READER_E2E_API_MODEL) {
        settings.readerE2eApiModel
    }.value
    val customHeaders = settings.observeAsState(AppSettings.KEY_READER_E2E_API_CUSTOM_HEADERS) {
        settings.readerE2eApiCustomHeaders
    }.value
    val concurrency = settings.observeAsState(AppSettings.KEY_READER_E2E_API_CONCURRENCY) {
        settings.readerE2eApiConcurrency.toString()
    }.value

    val presetOptions = listOf(
        SettingsChoiceOption("GEMINI", stringResource(R.string.reader_translation_e2e_api_provider_gemini)),
        SettingsChoiceOption("OLLAMA", stringResource(R.string.reader_translation_e2e_api_provider_ollama)),
        SettingsChoiceOption("CUSTOM", stringResource(R.string.ai_api_provider_custom)),
    )

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
                title = stringResource(R.string.reader_translation_e2e_api_settings_title),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsInfoPreference(
                    title = stringResource(R.string.reader_translation_e2e_api_intro_title),
                    summary = stringResource(R.string.reader_translation_e2e_api_intro_summary),
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.reader_translation_api_provider_preset),
                    summary = stringResource(R.string.reader_translation_api_provider_preset_summary),
                    value = providerPreset,
                    options = presetOptions,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_READER_E2E_API_PROVIDER_PRESET, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_endpoint),
                    summary = endpoint.ifEmpty { stringResource(R.string.reader_translation_api_endpoint_summary) },
                    value = endpoint,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_READER_E2E_API_ENDPOINT, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_key),
                    summary = apiKey.ifEmpty { stringResource(R.string.reader_translation_api_key_summary) },
                    value = apiKey,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_READER_E2E_API_KEY, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_model),
                    summary = model.ifEmpty { stringResource(R.string.reader_translation_api_model_summary) },
                    value = model,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_READER_E2E_API_MODEL, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_api_custom_headers),
                    summary = customHeaders.ifEmpty { stringResource(R.string.reader_translation_api_custom_headers_summary) },
                    value = customHeaders,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_READER_E2E_API_CUSTOM_HEADERS, value).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.reader_translation_e2e_api_concurrency),
                    summary = stringResource(R.string.reader_translation_e2e_api_concurrency_summary),
                    value = concurrency,
                    onValueChange = { value ->
                        value.toIntOrNull()?.let {
                            settings.prefs.edit().putString(AppSettings.KEY_READER_E2E_API_CONCURRENCY, it.toString()).apply()
                        }
                    },
                )
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = stringResource(R.string.reader_translation_api_models_fetch),
                    summary = stringResource(R.string.reader_translation_api_models_fetch_summary),
                    onClick = onFetchModels,
                )
            }
        }
    }
}
