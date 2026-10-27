package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.rememberTvFieldExit
import org.skepsun.kototoro.core.ui.adaptive.rememberTvTextFieldReadOnlyState
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** Hosts may keep their native dialog surface; the editor, draft and actions remain shared. */
class PreferenceDialogContent(
    val title: String,
    val onDismiss: () -> Unit,
    val bodyEditing: Boolean = false,
    val text: @Composable () -> Unit,
    val confirmButton: @Composable () -> Unit,
    val dismissButton: (@Composable () -> Unit)? = null,
)

@Composable
fun PreferenceDialog(content: PreferenceDialogContent) = PreferenceDialog(content, Modifier)

@Composable
fun PreferenceDialog(content: PreferenceDialogContent, modifier: Modifier) {
    AlertDialog(onDismissRequest = content.onDismiss, title = { Text(content.title) }, text = content.text,
        confirmButton = content.confirmButton, dismissButton = content.dismissButton, modifier = modifier)
}

data class PreferenceChoice<T>(val value: T, val label: String)

/** Android's choice list, with one selectable semantics node per option and TV focus support. */
@Composable
fun <T> PreferenceChoiceDialog(
    title: String,
    selected: T?,
    options: List<PreferenceChoice<T>>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    tagPrefix: String = "preference-choice",
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    renderDialog(PreferenceDialogContent(
        title = title,
        onDismiss = onDismiss,
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("$tagPrefix-dialog")) {
                itemsIndexed(options) { _, option ->
                    Row(Modifier.fillMaxWidth()
                        .tvFocusable(enabled = enabled, shape = RoundedCornerShape(10.dp), addFocusTarget = false)
                        .selectable(selected = option.value == selected, enabled = enabled,
                            onClick = { onSelect(option.value); onDismiss() })
                        .padding(vertical = 6.dp).testTag("$tagPrefix-option:${option.value}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.value == selected, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("$tagPrefix-close")) { Text(uiString("close")) }
        },
    ))
}

/** A private draft: cancel never writes, and a rejected save keeps the draft visible for correction. */
@Composable
fun PreferenceTextDialog(
    title: String,
    initial: String,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    secret: Boolean = false,
    rejectionMessage: String? = null,
    tagPrefix: String = "preference-text",
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var rejected by remember(initial) { mutableStateOf(false) }
    val fieldExit = rememberTvFieldExit()
    val editing = rememberTvTextFieldReadOnlyState()
    renderDialog(PreferenceDialogContent(
        title = title,
        onDismiss = onDismiss,
        bodyEditing = editing.isEditing,
        text = {
            Column(Modifier.fillMaxWidth().testTag("$tagPrefix-dialog")) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; rejected = false },
                    enabled = enabled,
                    singleLine = true,
                    readOnly = editing.isReadOnly,
                    isError = rejected,
                    supportingText = if (rejected && rejectionMessage != null) ({ Text(rejectionMessage) }) else null,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { fieldExit.onDone() }),
                    modifier = Modifier.fillMaxWidth().then(editing.fieldModifier).then(fieldExit.fieldModifier)
                        .testTag("$tagPrefix-field"),
                )
            }
        },
        confirmButton = {
            TextButton({ if (onSave(draft)) onDismiss() else rejected = true }, enabled = enabled,
                modifier = fieldExit.targetModifier.testTag("$tagPrefix-save")) { Text(uiString("save")) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDismiss, modifier = Modifier.testTag("$tagPrefix-cancel")) { Text(uiString("cancel")) }
                if (LocalUiPresentationConfig.current.isTv) {
                    TextButton({ draft = ""; rejected = false }, enabled = enabled,
                        modifier = Modifier.testTag("$tagPrefix-clear")) { Text(uiString("clear")) }
                }
            }
        },
    ))
}
