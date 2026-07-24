package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import javax.inject.Inject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.PlaybackSettingsScreen


@Composable
fun PlaybackSettingsRoute(
    settings: AppSettings,
    onAiSettingsClick: () -> Unit,
) {
    PlaybackSettingsScreen(
        settings = settings,
        onAiSettingsClick = onAiSettingsClick,
    )
}
