package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.ReaderSettingsScreen
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@Composable
fun ReaderSettingsRoute(
    settings: AppSettings,
    onReaderTapActionsClick: () -> Unit,
    onReplaceRulesClick: () -> Unit,
    onDictionaryRulesClick: () -> Unit,
) {
    ReaderSettingsScreen(
        settings = settings,
        onReaderTapActionsClick = onReaderTapActionsClick,
        onReplaceRulesClick = onReplaceRulesClick,
        onDictionaryRulesClick = onDictionaryRulesClick,
    )
}
