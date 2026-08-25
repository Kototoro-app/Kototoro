package org.skepsun.kototoro.settings.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.AboutSettingsScreen

@Composable
fun AboutSettingsRoute(
    settings: AppSettings,
    viewModel: AboutSettingsViewModel,
    onCheckUpdate: () -> Unit,
    onChangelogClick: () -> Unit,
    onLinkClick: (String) -> Unit,
    onCrashLogsClick: () -> Unit,
) {
    val isUpdateSupported by viewModel.isUpdateSupported.collectAsStateWithLifecycle(initialValue = false)
    val isUpdateAvailable by viewModel.isUpdateAvailable.collectAsStateWithLifecycle(initialValue = false)
    AboutSettingsScreen(
        settings = settings,
        isUpdateSupported = isUpdateSupported,
        isUpdateAvailable = isUpdateAvailable,
        onCheckUpdate = onCheckUpdate,
        onChangelogClick = onChangelogClick,
        onLinkClick = { key -> onLinkClick(key) },
        onCrashLogsClick = onCrashLogsClick,
    )
}
