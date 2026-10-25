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
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsGroupLabelRow
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/**
 * Android's per-source settings screen (`settings/compose/SourceSettingsScreen.kt` with `ComposePreferenceAdapter`):
 * each androidx `PreferenceCategory` becomes a titled section drawing one card of rows, a `TwoStatePreference` a
 * switch row, every other preference a value row that opens a dialog, and a group nested in another group a label row
 * — Android's `SourceSettingsGroupLabelRowUiState`. The Windows host receives the same tree as `SourcePreferenceNode`s
 * over the source protocol, so this file only folds that tree into sections and hands the rows to the shared
 * components in `core-ui` instead of drawing a flat column of cards.
 */

/** One entry of a section: either a source's own preference or the label of a group nested inside another group. */
internal sealed interface SourcePreferenceEntry {
    val id: String

    data class Row(val node: SourcePreferenceNode) : SourcePreferenceEntry {
        override val id: String get() = node.id
    }

    data class Label(override val id: String, val title: String) : SourcePreferenceEntry
}

/** One titled section of a source's settings; [entries] are the visible leaves plus nested group labels. */
internal data class SourcePreferenceSection(
    val id: String,
    val title: String,
    val entries: List<SourcePreferenceEntry>,
) {
    /** The leaf nodes of this section, in drawing order; dialog hosts look a row up by id. */
    val rows: List<SourcePreferenceNode> get() = entries.filterIsInstance<SourcePreferenceEntry.Row>().map { it.node }
}

private const val HOST_NODE_PREFIX = "host:"

/**
 * Folds the protocol tree into the sections Android builds from androidx Preferences: Kototoro's own `host:` rows
 * (User-Agent, domain) lead a section titled with Android's "settings" string, then every titled GROUP starts a
 * section of its own. An untitled GROUP joins the section being built, exactly as Android flattens a `PreferenceGroup`
 * into the rows around it, but a GROUP nested inside another one keeps its title as a label row — Android's
 * `SourceSettingsGroupLabelRowUiState` — so a source whose settings nest "Account → Advanced" does not lose that
 * structure in one flat card. Invisible nodes drop out and a section without rows disappears.
 */
internal fun sourcePreferenceSections(
    nodes: List<SourcePreferenceNode>,
    fallbackTitle: String,
): List<SourcePreferenceSection> {
    val sections = mutableListOf<SourcePreferenceSection>()
    val loose = mutableListOf<SourcePreferenceEntry>()
    var defaultSections = 0
    fun flushLoose() {
        if (loose.isEmpty()) return
        // A group between two loose rows produces Android's second "settings" section, so the id has to be unique.
        sections += SourcePreferenceSection("section:default:${defaultSections++}", fallbackTitle, loose.toList())
        loose.clear()
    }
    // A stable sort keeps the source's own order inside both buckets; Kototoro's rows lead, as they do on Android.
    val ordered = nodes.filter { it.visible }.sortedBy { if (it.id.startsWith(HOST_NODE_PREFIX)) 0 else 1 }
    for (node in ordered) {
        if (node.kind == SourcePreferenceKind.GROUP && node.title.isNotBlank()) {
            flushLoose()
            val entries = node.children.flatMap(::groupEntries)
            if (entries.isNotEmpty()) sections += SourcePreferenceSection("section:${node.id}", node.title, entries)
        } else {
            loose += groupEntries(node)
        }
    }
    flushLoose()
    return sections
}

/**
 * A GROUP carries no value of its own, so it contributes its visible children; a nested GROUP keeps a label row first,
 * which is how Android draws a group inside a category.
 */
private fun groupEntries(node: SourcePreferenceNode): List<SourcePreferenceEntry> = when {
    !node.visible -> emptyList()
    node.kind != SourcePreferenceKind.GROUP -> listOf(SourcePreferenceEntry.Row(node))
    else -> {
        val children = node.children.flatMap(::groupEntries)
        val label = node.title.takeIf(String::isNotBlank)
            ?.takeIf { children.isNotEmpty() }
            ?.let { listOf(SourcePreferenceEntry.Label("label:${node.id}", it)) }
            .orEmpty()
        label + children
    }
}

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
        sections.forEach { section ->
            SettingsSectionHeader(section.title)
            SettingsGroup {
                section.entries.forEachIndexed { index, entry ->
                    // A label groups the rows under it, so the card keeps its divider rhythm only between two rows.
                    if (index > 0 && entry is SourcePreferenceEntry.Row &&
                        section.entries[index - 1] is SourcePreferenceEntry.Row
                    ) {
                        SettingsRowDivider()
                    }
                    when (entry) {
                        is SourcePreferenceEntry.Label -> SettingsGroupLabelRow(entry.title)
                        is SourcePreferenceEntry.Row -> SourcePreferenceRow(controller, entry.node, enabled) { dialog = it }
                    }
                }
            }
        }
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
private fun SourcePreferenceRow(
    controller: DesktopController,
    node: SourcePreferenceNode,
    active: Boolean,
    onOpenDialog: (SourcePreferenceDialog) -> Unit,
) {
    val enabled = active && node.enabled
    val title = node.title.ifBlank { node.key.orEmpty() }
    val summary = node.summary.takeIf(String::isNotBlank)
    val tag = "preference:${node.id}"
    when (node.kind) {
        SourcePreferenceKind.TOGGLE -> SettingsSwitchRow(title, summary,
            checked = (node.value as? SourcePreferenceValue.Toggle)?.value ?: false, enabled = enabled,
            onCheckedChange = { controller.updatePreference(node, SourcePreferenceValue.Toggle(it)) }, testTag = tag)
        SourcePreferenceKind.CHOICE -> SettingsValueRow(title, summary, choiceLabel(node), enabled,
            onClick = { onOpenDialog(SourcePreferenceDialog.Choice(node.id)) }, testTag = tag)
        SourcePreferenceKind.MULTI_CHOICE -> SettingsValueRow(title, summary, multiChoiceLabel(node), enabled,
            onClick = { onOpenDialog(SourcePreferenceDialog.MultiChoice(node.id)) }, testTag = tag)
        SourcePreferenceKind.TEXT -> SettingsValueRow(title, summary, textLabel(node), enabled,
            onClick = { onOpenDialog(SourcePreferenceDialog.Text(node.id)) }, testTag = tag)
        SourcePreferenceKind.ACTION -> SettingsValueRow(title, summary, null, enabled,
            onClick = { controller.updatePreference(node, SourcePreferenceValue.Action()) }, testTag = tag)
        SourcePreferenceKind.INFO -> SettingsInfoRow(title, summary)
        SourcePreferenceKind.UNSUPPORTED -> SettingsInfoRow(title, summary ?: desktopText("preference.unsupported"))
        // sourcePreferenceSections never hands out a GROUP node: its title became a label entry, its children rows.
        SourcePreferenceKind.GROUP -> Unit
    }
}

/** Android shows a choice's label, not its raw value, and joins the selected multi-choice labels the same way. */
private fun choiceLabel(node: SourcePreferenceNode): String? {
    val value = (node.value as? SourcePreferenceValue.Text)?.value ?: return null
    return node.choices.firstOrNull { it.value == value }?.title ?: value
}

private fun multiChoiceLabel(node: SourcePreferenceNode): String? {
    val values = (node.value as? SourcePreferenceValue.TextSet)?.values.orEmpty()
    if (values.isEmpty()) return null
    return node.choices.filter { it.value in values }.joinToString(", ") { it.title }.ifEmpty { values.size.toString() }
}

/** A secret keeps its length out of sight; a blank text row shows no value at all, like Android's empty summary. */
private fun textLabel(node: SourcePreferenceNode): String? {
    val value = (node.value as? SourcePreferenceValue.Text)?.value.orEmpty()
    return when {
        value.isEmpty() -> null
        node.isSecret -> "•".repeat(value.length.coerceAtMost(12))
        else -> value
    }
}

@Composable
private fun SourcePreferenceDialogHost(
    controller: DesktopController,
    node: SourcePreferenceNode,
    dialog: SourcePreferenceDialog,
    onDismiss: () -> Unit,
) {
    val title = node.title.ifBlank { node.key.orEmpty() }
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
            onToggle = { value, checked ->
                val values = (node.value as? SourcePreferenceValue.TextSet)?.values.orEmpty()
                controller.updatePreference(node, SourcePreferenceValue.TextSet(
                    if (checked) values - value else values + value))
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
