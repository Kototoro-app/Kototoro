package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourcePreferenceEntry
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceSection
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.sourcePreferenceRowTitle
import org.skepsun.kototoro.core.source.sourcePreferenceValueLabel

/**
 * Android's per-source settings page drawn from the source protocol's preference tree: each `PreferenceCategory`
 * arrives as a titled section (one card of rows under a section header), a `TwoStatePreference` as a switch row, every
 * other control as a value row that opens a dialog, and a group nested in another group as a label row. The folding
 * itself lives next to the DTOs (`sourcePreferenceSections`), so a host that has the tree does not re-derive it.
 *
 * The page is presentation only: which dialog a choice row opens, how a value is written back and whether the source
 * rejects it stay in the host, which is why every row action hands the node back instead of taking a value.
 */
@Composable
fun SourcePreferencesContent(
    sections: List<SourcePreferenceSection>,
    active: Boolean,
    unsupportedText: String,
    onToggle: (SourcePreferenceNode, Boolean) -> Unit,
    onOpenChoice: (SourcePreferenceNode) -> Unit,
    onOpenMultiChoice: (SourcePreferenceNode) -> Unit,
    onOpenText: (SourcePreferenceNode) -> Unit,
    onAction: (SourcePreferenceNode) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "preference",
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(4.dp),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = verticalArrangement) {
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
                        is SourcePreferenceEntry.Row -> SourcePreferenceRow(
                            node = entry.node,
                            active = active,
                            unsupportedText = unsupportedText,
                            tag = "$tagPrefix:${entry.node.id}",
                            onToggle = onToggle,
                            onOpenChoice = onOpenChoice,
                            onOpenMultiChoice = onOpenMultiChoice,
                            onOpenText = onOpenText,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePreferenceRow(
    node: SourcePreferenceNode,
    active: Boolean,
    unsupportedText: String,
    tag: String,
    onToggle: (SourcePreferenceNode, Boolean) -> Unit,
    onOpenChoice: (SourcePreferenceNode) -> Unit,
    onOpenMultiChoice: (SourcePreferenceNode) -> Unit,
    onOpenText: (SourcePreferenceNode) -> Unit,
    onAction: (SourcePreferenceNode) -> Unit,
) {
    val enabled = active && node.enabled
    val title = sourcePreferenceRowTitle(node)
    val summary = node.summary.takeIf(String::isNotBlank)
    when (node.kind) {
        SourcePreferenceKind.TOGGLE -> SettingsSwitchRow(title, summary,
            checked = (node.value as? SourcePreferenceValue.Toggle)?.value ?: false, enabled = enabled,
            onCheckedChange = { onToggle(node, it) }, testTag = tag)
        SourcePreferenceKind.CHOICE -> SettingsValueRow(title, summary, sourcePreferenceValueLabel(node), enabled,
            onClick = { onOpenChoice(node) }, testTag = tag)
        SourcePreferenceKind.MULTI_CHOICE -> SettingsValueRow(title, summary, sourcePreferenceValueLabel(node), enabled,
            onClick = { onOpenMultiChoice(node) }, testTag = tag)
        SourcePreferenceKind.TEXT -> SettingsValueRow(title, summary, sourcePreferenceValueLabel(node), enabled,
            onClick = { onOpenText(node) }, testTag = tag)
        SourcePreferenceKind.ACTION -> SettingsValueRow(title, summary, null, enabled,
            onClick = { onAction(node) }, testTag = tag)
        SourcePreferenceKind.INFO -> SettingsInfoRow(title, summary, Modifier.testTag(tag))
        SourcePreferenceKind.UNSUPPORTED -> SettingsInfoRow(title, summary ?: unsupportedText, Modifier.testTag(tag))
        // sourcePreferenceSections never hands out a GROUP node: its title became a label entry, its children rows.
        SourcePreferenceKind.GROUP -> Unit
    }
}
