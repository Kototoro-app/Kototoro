package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The dialogs Android's settings screens open for a preference that cannot be edited inline: a single choice list, a
 * check-box list that commits each toggle, and a text field. Every Windows settings screen (source preferences,
 * recommendations, extension management) shares them so the option tags keep one shape.
 */

/** A radio list; picking a value commits it and closes the dialog, like Android's `SettingsChoiceDialog`. */
@Composable
internal fun DesktopChoiceSettingsDialog(
    title: String,
    summary: String?,
    bodyTag: String,
    optionTagPrefix: String,
    options: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(420.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag(bodyTag)) {
                SettingsDialogSummary(summary)
                options.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                        onPick(value)
                        onDismiss()
                    }.padding(vertical = 4.dp).testTag("$optionTagPrefix:$value"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("$bodyTag-close")) { Text(desktopText("common.close")) }
        },
    )
}

/** A check-box list: each toggle commits immediately and stays open, so several values can be changed in one visit. */
@Composable
internal fun DesktopMultiChoiceSettingsDialog(
    title: String,
    summary: String?,
    bodyTag: String,
    optionTagPrefix: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (value: String, checked: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(420.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag(bodyTag)) {
                SettingsDialogSummary(summary)
                options.forEach { (label, value) ->
                    val checked = value in selected
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onToggle(value, checked) }
                        .padding(vertical = 4.dp).testTag("$optionTagPrefix:$value"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("$bodyTag-close")) { Text(desktopText("common.close")) }
        },
    )
}

/** A single field; the caller parses and persists the text, so an invalid value keeps the dialog open. */
@Composable
internal fun DesktopTextSettingsDialog(
    title: String,
    summary: String?,
    bodyTag: String,
    fieldTag: String,
    saveTag: String,
    initial: String,
    enabled: Boolean,
    secret: Boolean = false,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(460.dp).testTag(bodyTag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsDialogSummary(summary)
                OutlinedTextField(text, { text = it }, enabled = enabled, singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().testTag(fieldTag))
            }
        },
        confirmButton = {
            TextButton({ if (onSave(text)) onDismiss() }, enabled = enabled, modifier = Modifier.testTag(saveTag)) {
                Text(desktopText("common.save"))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(desktopText("common.cancel")) } },
    )
}

@Composable
private fun SettingsDialogSummary(summary: String?) {
    if (summary != null) Text(summary, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
}
