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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun SuggestionsSettingsScreen(
    settings: AppSettings,
    excludeTags: String,
    preferredTags: String,
    onExcludeTagsChanged: (String) -> Unit,
    onPreferredTagsChanged: (String) -> Unit,
) {
    val isEnabled by settings.observeAsState(AppSettings.KEY_SUGGESTIONS) { isSuggestionsEnabled }
    val isWifiOnly by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY) { isSuggestionsWiFiOnly }
    val includeDisabledSources by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_DISABLED_SOURCES) {
        isSuggestionsIncludeDisabledSources
    }
    val notificationsEnabled by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS) {
        isSuggestionsNotificationAvailable
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
                title = stringResource(R.string.suggestions),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.suggestions_enable),
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        settings.isSuggestionsEnabled = checked
                    },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.only_using_wifi),
                    summary = stringResource(R.string.suggestions_wifi_only_summary),
                    checked = isWifiOnly,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY, checked).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.include_disabled_sources),
                    summary = stringResource(R.string.suggestions_disabled_sources_summary),
                    checked = includeDisabledSources,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_SUGGESTIONS_DISABLED_SOURCES, checked).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.notifications_enable),
                    summary = stringResource(R.string.suggestions_notifications_summary),
                    checked = notificationsEnabled,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS, checked).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.suggestions_excluded_genres),
                    summary = excludeTags.ifEmpty { stringResource(R.string.suggestions_excluded_genres_summary) },
                    value = excludeTags,
                    enabled = isEnabled,
                    onValueChange = onExcludeTagsChanged,
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.suggestions_preferred_genres),
                    summary = preferredTags.ifEmpty { stringResource(R.string.suggestions_preferred_genres_summary) },
                    value = preferredTags,
                    enabled = isEnabled,
                    onValueChange = onPreferredTagsChanged,
                )
                SettingsSectionDivider()
                SettingsInfoPreference(
                    title = stringResource(R.string.suggestions_info),
                    summary = "",
                )
            }
        }
    }
}
