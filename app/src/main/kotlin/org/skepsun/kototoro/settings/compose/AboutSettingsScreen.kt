package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.VersionId
import org.skepsun.kototoro.core.github.isStable
import org.skepsun.kototoro.core.prefs.AppSettings

@Composable
fun AboutSettingsScreen(
    settings: AppSettings,
    isUpdateSupported: Boolean,
    isLoading: Boolean,
    onCheckUpdate: () -> Unit,
    onChangelogClick: () -> Unit,
    onLinkClick: (key: String) -> Unit,
    onCrashLogsClick: () -> Unit,
) {
    val isStableVersion = VersionId(BuildConfig.VERSION_NAME).isStable

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "about_overview") {
                SettingsPreferenceSection(title = stringResource(R.string.about)) {
                    SettingsActionPreference(
                        title = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                        summary = stringResource(R.string.check_for_updates),
                        enabled = isUpdateSupported && !isLoading,
                        onClick = onCheckUpdate,
                    )
                    if (isUpdateSupported) {
                        SettingsSectionDivider()
                        SettingsSwitchPreference(
                            title = stringResource(R.string.allow_unstable_updates),
                            summary = stringResource(R.string.allow_unstable_updates_summary),
                            checked = if (isStableVersion) {
                                settings.prefs.getBoolean(AppSettings.KEY_UPDATES_UNSTABLE, false)
                            } else {
                                true
                            },
                            enabled = isStableVersion,
                            onCheckedChange = { checked ->
                                settings.prefs.edit().putBoolean(AppSettings.KEY_UPDATES_UNSTABLE, checked).apply()
                            },
                        )
                    }
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.changelog),
                        summary = stringResource(R.string.changelog_summary),
                        onClick = onChangelogClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.crash_logs),
                        summary = stringResource(R.string.crash_logs_summary),
                        onClick = onCrashLogsClick,
                    )
                }
            }
            item(key = "about_links") {
                SettingsPreferenceSection(title = stringResource(R.string.more)) {
                    SettingsActionPreference(
                        title = stringResource(R.string.user_manual),
                        summary = stringResource(R.string.url_user_manual),
                        onClick = { onLinkClick(AppSettings.KEY_LINK_MANUAL) },
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.source_code),
                        summary = stringResource(R.string.url_github),
                        onClick = { onLinkClick(AppSettings.KEY_LINK_GITHUB) },
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.about_donate),
                        summary = stringResource(R.string.url_donate),
                        onClick = { onLinkClick(AppSettings.KEY_LINK_DONATE) },
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.about_discord),
                        summary = stringResource(R.string.url_discord),
                        onClick = { onLinkClick(AppSettings.KEY_LINK_DISCORD) },
                    )
                }
            }
        }
    }
}
