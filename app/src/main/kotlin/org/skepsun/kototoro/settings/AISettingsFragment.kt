package org.skepsun.kototoro.settings


import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.AISettingsScreen
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@Composable
fun AISettingsRoute(
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onOpenImageEnhancementSettings: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenVideoEnhancementSettings: () -> Unit,
) {
    AISettingsScreen(
        onOpenOcrModels = onOpenOcrModels,
        onOpenApiSettings = onOpenApiSettings,
        onOpenTranslationSettings = onOpenTranslationSettings,
        onOpenImageEnhancementSettings = onOpenImageEnhancementSettings,
        onOpenTtsSettings = onOpenTtsSettings,
        onOpenVideoEnhancementSettings = onOpenVideoEnhancementSettings,
    )
}
