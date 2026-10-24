package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Android's `IncognitoModeDialog`: the question its reader raises for an adult work while `incognito_nsfw` says ASK.
 * The three answers are the same — Disable, Cancel, Incognito — and "Don't ask again" stores the picked answer in the
 * tri-state the source settings page edits, so both entry points stay one choice.
 */
@Composable
internal fun DesktopIncognitoDialog(
    prompt: DesktopIncognitoPrompt,
    enabled: Boolean,
    onAnswer: (incognito: Boolean) -> Unit,
    onDontAskAgain: (enabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("incognito-dialog"),
        title = { Text(AndroidStrings["incognito_mode"]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AndroidStrings["incognito_mode_hint_nsfw"])
                Row(
                    modifier = Modifier.testTag("incognito-dont-ask")
                        .toggleable(value = prompt.dontAskAgain, enabled = enabled, role = Role.Checkbox,
                            onValueChange = onDontAskAgain),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(prompt.dontAskAgain, onCheckedChange = null, enabled = enabled)
                    Text(AndroidStrings["dont_ask_again"], Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onAnswer(false) }, enabled = enabled,
                    modifier = Modifier.testTag("incognito-disable")) {
                    Text(AndroidStrings["disable"])
                }
                TextButton(onDismiss, enabled = enabled,
                    modifier = Modifier.testTag("incognito-cancel")) {
                    Text(AndroidStrings["cancel"])
                }
                TextButton({ onAnswer(true) }, enabled = enabled,
                    modifier = Modifier.testTag("incognito-accept")) {
                    Text(AndroidStrings["incognito"])
                }
            }
        },
    )
}

/** The details-page counterpart of Android's dock "无痕模式" item: read once without writing anything anywhere. */
@Composable
internal fun DesktopIncognitoReadingButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, enabled = enabled, modifier = modifier.testTag("preview-incognito")) {
        Text(AndroidStrings["incognito"])
    }
}
