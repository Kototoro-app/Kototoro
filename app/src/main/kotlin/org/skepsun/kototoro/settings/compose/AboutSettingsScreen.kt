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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap

@Composable
fun AboutSettingsScreen(
    settings: AppSettings,
    isUpdateSupported: Boolean,
    isUpdateAvailable: Boolean,
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(8.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "about_banner") {
                AboutBanner()
            }
            item(key = "about_overview") {
                SettingsPreferenceGroup(title = stringResource(R.string.about)) {
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                            iconRes = R.drawable.ic_app_update,
                            summary = stringResource(R.string.check_for_updates),
                            enabled = isUpdateSupported && !isLoading,
                            showUpdateBadge = isUpdateAvailable,
                            onClick = onCheckUpdate,
                        )
                    }
                    if (isUpdateSupported) {
                        item {
                            SettingsSwitchPreference(
                                title = stringResource(R.string.allow_unstable_updates),
                                iconRes = R.drawable.ic_new,
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
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.changelog),
                            iconRes = R.drawable.ic_history_selector,
                            summary = stringResource(R.string.changelog_summary),
                            onClick = onChangelogClick,
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.crash_logs),
                            iconRes = R.drawable.ic_error_small,
                            summary = stringResource(R.string.crash_logs_summary),
                            onClick = onCrashLogsClick,
                        )
                    }
                }
            }
            item(key = "about_links") {
                SettingsPreferenceGroup(title = stringResource(R.string.about_group_support)) {
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.user_manual),
                            iconRes = R.drawable.ic_read,
                            summary = stringResource(R.string.url_user_manual),
                            onClick = { onLinkClick(AppSettings.KEY_LINK_MANUAL) },
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.source_code),
                            iconRes = R.drawable.ic_code,
                            summary = stringResource(R.string.url_github),
                            onClick = { onLinkClick(AppSettings.KEY_LINK_GITHUB) },
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.about_donate),
                            iconRes = R.drawable.ic_heart_outline,
                            summary = stringResource(R.string.url_donate),
                            onClick = { onLinkClick(AppSettings.KEY_LINK_DONATE) },
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.about_donate_afdian),
                            iconRes = R.drawable.ic_heart_outline,
                            summary = stringResource(R.string.url_afdian),
                            onClick = { onLinkClick(AppSettings.KEY_LINK_AFDIAN) },
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.about_discord),
                            iconRes = R.drawable.ic_discord,
                            summary = stringResource(R.string.url_discord),
                            onClick = { onLinkClick(AppSettings.KEY_LINK_DISCORD) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutBanner() {
    val context = LocalContext.current
    val appIcon = remember(context) {
        runCatching {
            context.applicationInfo.loadIcon(context.packageManager)
                .toBitmap(192, 192)
                .asImageBitmap()
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.about_author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}
