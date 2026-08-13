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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.reader.translate.domain.TranslationApiProviderCatalog

@Composable
fun TranslationApiSettingsScreen(
    settings: AppSettings,
    onFetchModelsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs
    val uriHandler = LocalUriHandler.current

    val currentPreset = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET) {
        settings.readerTranslationApiProviderPreset
    }.value
    val provider = TranslationApiProviderCatalog.find(currentPreset)

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
            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_api_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.reader_translation_api_provider_preset),
                        iconRes = R.drawable.ic_key,
                        options = listOf(
                            SettingsChoiceOption(
                                "CUSTOM",
                                stringResource(R.string.reader_translation_api_provider_custom),
                            ),
                        ) + TranslationApiProviderCatalog.providers.map { preset ->
                            SettingsChoiceOption(preset.id, preset.name)
                        },
                        value = currentPreset,
                        onValueChange = {
                            settings.prefs.edit {
                                putString(AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET, it)
                            }
                        },
                    )
                }
                if (provider != null) {
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.reader_translation_api_get_key),
                            iconRes = R.drawable.ic_key,
                            summary = stringResource(R.string.reader_translation_api_get_key_summary),
                            onClick = { uriHandler.openUri(provider.apiKeyUrl) },
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.reader_translation_api_open_docs),
                            iconRes = R.drawable.ic_open_external,
                            summary = stringResource(R.string.reader_translation_api_open_docs_summary),
                            onClick = { uriHandler.openUri(provider.documentationUrl) },
                        )
                    }
                } else {
                    item {
                        SettingsTextInputPreference(
                            title = stringResource(R.string.reader_translation_api_endpoint),
                            iconRes = R.drawable.ic_web,
                            summary = stringResource(R.string.reader_translation_api_endpoint_summary),
                            value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT) {
                                prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT, "") ?: ""
                            }.value,
                            onValueChange = {
                                settings.prefs.edit {
                                    putString(AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT, it)
                                }
                            },
                        )
                    }
                }
                item {
                    SettingsTextInputPreference(
                        title = stringResource(R.string.reader_translation_api_key),
                        iconRes = R.drawable.ic_key,
                        summary = stringResource(R.string.reader_translation_api_key_summary),
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_KEY) {
                            prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_KEY, "") ?: ""
                        }.value,
                        isPassword = true,
                        onValueChange = {
                            settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_KEY, it) }
                        },
                    )
                }
                item {
                    SettingsTextInputPreference(
                        title = stringResource(R.string.reader_translation_api_model),
                        iconRes = R.drawable.ic_auto_fix,
                        summary = stringResource(R.string.reader_translation_api_model_summary),
                        value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_MODEL) {
                            prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_MODEL, "gpt-4o-mini")
                                ?: "gpt-4o-mini"
                        }.value,
                        onValueChange = {
                            settings.prefs.edit { putString(AppSettings.KEY_READER_TRANSLATION_API_MODEL, it) }
                        },
                    )
                }
                if (provider == null) {
                    item {
                        SettingsTextInputPreference(
                            title = stringResource(R.string.reader_translation_api_custom_headers),
                            iconRes = R.drawable.ic_code,
                            summary = stringResource(R.string.reader_translation_api_custom_headers_summary),
                            value = settings.observeAsState(AppSettings.KEY_READER_TRANSLATION_API_CUSTOM_HEADERS) {
                                prefs.getString(AppSettings.KEY_READER_TRANSLATION_API_CUSTOM_HEADERS, "") ?: ""
                            }.value,
                            onValueChange = {
                                settings.prefs.edit {
                                    putString(AppSettings.KEY_READER_TRANSLATION_API_CUSTOM_HEADERS, it)
                                }
                            },
                        )
                    }
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.reader_translation_api_models_fetch),
                        iconRes = R.drawable.ic_cloud_download,
                        summary = stringResource(R.string.reader_translation_api_models_fetch_summary),
                        onClick = onFetchModelsClick,
                    )
                }
            }
        }
    }
}
