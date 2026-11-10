package org.skepsun.kototoro.settings


import androidx.compose.runtime.Composable
import org.skepsun.kototoro.settings.compose.AISettingsScreen

@Composable
fun AISettingsRoute(
    onOpenOcrModels: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onOpenImageEnhancementSettings: () -> Unit,
    onOpenTtsSettings: () -> Unit,
) {
    AISettingsScreen(
        onOpenOcrModels = onOpenOcrModels,
        onOpenApiSettings = onOpenApiSettings,
        onOpenTranslationSettings = onOpenTranslationSettings,
        onOpenImageEnhancementSettings = onOpenImageEnhancementSettings,
        onOpenTtsSettings = onOpenTtsSettings,
    )
}
