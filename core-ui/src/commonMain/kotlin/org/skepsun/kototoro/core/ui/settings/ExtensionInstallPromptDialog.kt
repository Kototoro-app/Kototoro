package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** The host owns request identity, checkbox restoration and the actual installation/policy write. */
@Composable
fun ExtensionInstallPromptDialog(
    name: String,
    sourceCount: Int,
    kindLabel: String,
    rememberChoice: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onChoose: (enableSources: Boolean) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = {
        PreferenceDialog(it, Modifier.testTag("install-prompt-dialog"))
    },
) {
    renderDialog(PreferenceDialogContent(
        title = uiString("extension_install_choice_title", name),
        onDismiss = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (sourceCount > 0) uiString("extension_install_choice_message", sourceCount)
                    else uiString("extension_install_choice_message_unknown"))
                Row(Modifier.fillMaxWidth().testTag("install-prompt-remember")
                    .tvFocusable(enabled = enabled, addFocusTarget = false)
                    .toggleable(rememberChoice, enabled = enabled, role = Role.Checkbox, onValueChange = onRememberChange),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberChoice, onCheckedChange = null, enabled = enabled)
                    Text(uiString("extension_install_remember_choice", kindLabel))
                }
            }
        },
        confirmButton = {
            Button({ onChoose(true) }, enabled = enabled,
                modifier = Modifier.testTag("install-prompt-enable").tvFocusable(enabled = enabled, addFocusTarget = false)) {
                Text(uiString("extension_install_and_enable"))
            }
        },
        dismissButton = {
            Row {
                TextButton(onDismiss, modifier = Modifier.testTag("install-prompt-cancel")
                    .tvFocusable(addFocusTarget = false)) { Text(uiString("cancel")) }
                TextButton({ onChoose(false) }, enabled = enabled,
                    modifier = Modifier.testTag("install-prompt-only").tvFocusable(enabled = enabled, addFocusTarget = false)) {
                    Text(uiString("extension_install_only"))
                }
            }
        },
    ))
}
