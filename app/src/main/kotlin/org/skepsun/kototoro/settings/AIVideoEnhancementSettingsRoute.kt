package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.AIVideoEnhancementSettingsScreen
import javax.inject.Inject

@Composable
fun AIVideoEnhancementSettingsRoute(
    settings: AppSettings,
) {
    AIVideoEnhancementSettingsScreen(
        settings = settings,
    )
}
