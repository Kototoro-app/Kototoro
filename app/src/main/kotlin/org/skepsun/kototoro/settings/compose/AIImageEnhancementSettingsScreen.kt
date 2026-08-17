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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun AIImageEnhancementSettingsScreen(
    settings: AppSettings,
    ncnnModels: List<SettingsChoiceOption<String>>,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs

    val engineNames = stringArrayResource(R.array.values_reader_super_resolution_engines).toList()
    val anime4kNames = stringArrayResource(R.array.values_reader_super_resolution_anime4k_modes).toList()

    val persistedIsEnabled = settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_ENABLED) {
        settings.isReaderSuperResolutionEnabled
    }.value
    var isEnabled by remember(persistedIsEnabled) { mutableStateOf(persistedIsEnabled) }
    val persistedEngine = settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_ENGINE) {
        settings.readerSuperResolutionEngine
    }.value
    var engine by remember(persistedEngine) { mutableStateOf(persistedEngine) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = settingsContentTopInset())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceGroup(
                title = stringResource(R.string.ai_image_enhancement_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.reader_super_resolution),
                        iconRes = R.drawable.ic_zoom_in,
                        summary = stringResource(R.string.reader_super_resolution_summary),
                        checked = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            settings.prefs.edit { putBoolean(AppSettings.KEY_READER_SUPER_RESOLUTION_ENABLED, it) }
                        },
                    )
                }

                if (isEnabled) {
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.reader_super_resolution_engine),
                            iconRes = R.drawable.ic_services,
                            options = stringArrayResource(R.array.reader_super_resolution_engines)
                                .mapIndexed { index, label -> SettingsChoiceOption(engineNames[index], label) },
                            value = engine,
                            onValueChange = {
                                engine = it
                                settings.prefs.edit { putString(AppSettings.KEY_READER_SUPER_RESOLUTION_ENGINE, it) }
                            },
                        )
                    }

                    if (engine == "ANIME4K" || engine == "VULKAN") {
                        item {
                            SettingsChoicePreference(
                                title = stringResource(R.string.reader_super_resolution_anime4k_mode),
                                iconRes = R.drawable.ic_auto_fix,
                                options = stringArrayResource(R.array.video_super_resolution_shaders)
                                    .mapIndexed { index, label -> SettingsChoiceOption(anime4kNames[index], label) },
                                value = settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_ANIME4K_MODE) {
                                    prefs.getString(
                                        AppSettings.KEY_READER_SUPER_RESOLUTION_ANIME4K_MODE,
                                        "ANIME4K_A",
                                    ) ?: "ANIME4K_A"
                                }.value,
                                onValueChange = {
                                    settings.prefs.edit {
                                        putString(AppSettings.KEY_READER_SUPER_RESOLUTION_ANIME4K_MODE, it)
                                    }
                                },
                            )
                        }
                    }

                    if (engine == "NCNN") {
                        item {
                            SettingsChoicePreference(
                                title = stringResource(R.string.reader_super_resolution_model),
                                iconRes = R.drawable.ic_dice,
                                options = ncnnModels,
                                value = settings.observeAsState(AppSettings.KEY_READER_SUPER_RESOLUTION_MODEL) {
                                    prefs.getString(AppSettings.KEY_READER_SUPER_RESOLUTION_MODEL, "SE") ?: "SE"
                                }.value,
                                onValueChange = {
                                    settings.prefs.edit { putString(AppSettings.KEY_READER_SUPER_RESOLUTION_MODEL, it) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
