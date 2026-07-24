package org.skepsun.kototoro.settings.about

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.SettingsDestination
import org.skepsun.kototoro.settings.compose.AboutSettingsScreen
import javax.inject.Inject

@Composable
fun AboutSettingsRoute(
    settings: AppSettings,
    viewModel: AboutSettingsViewModel,
    onChangelogClick: () -> Unit,
    onLinkClick: (String) -> Unit,
    onCrashLogsClick: () -> Unit,
) {
    val isUpdateSupported by viewModel.isUpdateSupported.collectAsState(initial = false)
    val isLoading by viewModel.isLoading.collectAsState(initial = false)

    AboutSettingsScreen(
        settings = settings,
        isUpdateSupported = isUpdateSupported,
        isLoading = isLoading,
        onCheckUpdate = { viewModel.checkForUpdates() },
        onChangelogClick = onChangelogClick,
        onLinkClick = { key -> onLinkClick(key) },
        onCrashLogsClick = onCrashLogsClick,
    )
}
