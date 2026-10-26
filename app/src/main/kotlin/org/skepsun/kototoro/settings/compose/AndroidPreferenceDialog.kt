package org.skepsun.kototoro.settings.compose

import androidx.compose.runtime.Composable
import org.skepsun.kototoro.core.ui.settings.PreferenceDialogContent

/** Keep Android artwork/glass and TV dialog focus handling around the shared editor. */
@Composable
internal fun AndroidPreferenceDialog(content: PreferenceDialogContent) {
    SettingsAlertDialog(title = content.title, onDismissRequest = content.onDismiss, bodyEditing = content.bodyEditing,
        text = content.text, confirmButton = content.confirmButton, dismissButton = content.dismissButton)
}
