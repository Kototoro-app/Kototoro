package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.sourcePreferenceRowTitle
import org.skepsun.kototoro.core.source.sourcePreferenceSections
import org.skepsun.kototoro.core.ui.settings.SourcePreferencesContent

/**
 * Android's per-source settings screen (`settings/compose/SourceSettingsScreen.kt` with `ComposePreferenceAdapter`).
 * The Windows host receives the same tree as `SourcePreferenceNode`s over the source protocol, so this file only folds
 * it into Android's sections (`sourcePreferenceSections` — shared with the DTOs), hands the rows to
 * `SourcePreferencesContent` in `core-ui`, and owns what stays local: the back button, the three dialogs that edit a
 * value, and the controller calls that write it back.
 */

@Composable
internal fun DesktopSourcePreferences(controller: DesktopController, state: DesktopAppState) {
    val screen = state.preferences ?: return
    val enabled = !state.busy
    val sections = remember(screen) { sourcePreferenceSections(screen.nodes, AndroidStrings["settings"]) }
    // Dialogs survive a revision change (every update returns a fresh screen), so a multi-choice picker stays open.
    var dialog by remember(screen.source.name) { mutableStateOf<SourcePreferenceDialog?>(null) }
    val rows = remember(sections) { sections.flatMap { it.rows }.associateBy(SourcePreferenceNode::id) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.browse() }, enabled = enabled) { Text(desktopText("browse.backToBrowse")) }
        SourcePreferencesContent(
            sections = sections,
            active = enabled,
            unsupportedText = desktopText("preference.unsupported"),
            onToggle = { node, checked -> controller.updatePreference(node, SourcePreferenceValue.Toggle(checked)) },
            onOpenChoice = { node -> dialog = SourcePreferenceDialog.Choice(node.id) },
            onOpenMultiChoice = { node -> dialog = SourcePreferenceDialog.MultiChoice(node.id) },
            onOpenText = { node -> dialog = SourcePreferenceDialog.Text(node.id) },
            onAction = { node -> controller.updatePreference(node, SourcePreferenceValue.Action()) },
        )
    }
    dialog?.let { active -> rows[active.nodeId]?.let { node -> SourcePreferenceDialogHost(controller, node, active) { dialog = null } } }
}

private sealed interface SourcePreferenceDialog {
    val nodeId: String

    data class Choice(override val nodeId: String) : SourcePreferenceDialog
    data class MultiChoice(override val nodeId: String) : SourcePreferenceDialog
    data class Text(override val nodeId: String) : SourcePreferenceDialog
}

@Composable
private fun SourcePreferenceDialogHost(
    controller: DesktopController,
    node: SourcePreferenceNode,
    dialog: SourcePreferenceDialog,
    onDismiss: () -> Unit,
) {
    val title = sourcePreferenceRowTitle(node)
    val summary = node.summary.takeIf(String::isNotBlank)
    val options = node.choices.map { it.title to it.value }
    val bodyTag = "preference-dialog:${node.id}"
    val optionTags = "preference:${node.id}"
    when (dialog) {
        is SourcePreferenceDialog.Choice -> DesktopChoiceSettingsDialog(title, summary, bodyTag, optionTags, options,
            selected = (node.value as? SourcePreferenceValue.Text)?.value, enabled = node.enabled,
            onPick = { controller.updatePreference(node, SourcePreferenceValue.Text(it)) }, onDismiss = onDismiss)
        is SourcePreferenceDialog.MultiChoice -> DesktopMultiChoiceSettingsDialog(title, summary, bodyTag, optionTags,
            options, selected = (node.value as? SourcePreferenceValue.TextSet)?.values.orEmpty(), enabled = node.enabled,
            onValueChange = { values ->
                controller.updatePreference(node, SourcePreferenceValue.TextSet(values))
            }, onDismiss = onDismiss)
        is SourcePreferenceDialog.Text -> DesktopTextSettingsDialog(title, summary, bodyTag,
            fieldTag = "preference-field:${node.id}", saveTag = "preference-save:${node.id}",
            initial = (node.value as? SourcePreferenceValue.Text)?.value.orEmpty(), enabled = node.enabled,
            secret = node.isSecret, onSave = { text ->
                controller.updatePreference(node, SourcePreferenceValue.Text(text))
                true
            }, onDismiss = onDismiss)
    }
}
