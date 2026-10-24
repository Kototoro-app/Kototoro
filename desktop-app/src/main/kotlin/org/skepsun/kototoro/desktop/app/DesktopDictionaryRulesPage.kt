package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Android's dictionary-rules settings screen (`DictionaryRulesActivity`) for the Windows host.
 *
 * Android shows one card per rule — name, URL rule, an enable switch and a delete button — and edits it in a dialog
 * with the same five fields; kept here on the shared settings rows, with import/export going through the shared JSON
 * codec the Android SAF flow uses.
 */
@Composable
internal fun DesktopDictionaryRulesPage(controller: DesktopController, state: DesktopAppState) {
    val ui by controller.dictionaryRules.collectAsState()
    val active = !state.busy
    val rules = ui.rules
    Column(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.backFromDictionaryRules() }, enabled = active,
                modifier = Modifier.testTag("dictionary-rules-back")) { Text(desktopText("common.back")) }
            Spacer(Modifier.width(12.dp))
            Text(AndroidStrings["dictionary_rules"], style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton({ importDictionaryRules(controller) }, enabled = active,
                modifier = Modifier.testTag("dictionary-rules-import")) { Text(AndroidStrings["import_button"]) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ exportDictionaryRules(controller) }, enabled = active && !rules.isNullOrEmpty(),
                modifier = Modifier.testTag("dictionary-rules-export")) { Text(AndroidStrings["export"]) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ controller.openDictionaryRuleEditor(null) }, enabled = active,
                modifier = Modifier.testTag("dictionary-rules-add")) { Text(AndroidStrings["add"]) }
        }
        Text(AndroidStrings["dictionary_rules_summary"], style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.testTag("dictionary-rules-summary"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            // Null means the table has not been read yet: an empty list is a real answer, "not asked" is not.
            rules == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(desktopText("dictionaryRules.loading"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("dictionary-rules-loading"))
            }

            rules.isEmpty() -> SettingsInfoRow(AndroidStrings["dictionary_rules_empty"], null,
                Modifier.testTag("dictionary-rules-empty"))

            else -> rules.forEachIndexed { index, rule ->
                if (index > 0) SettingsRowDivider()
                SettingsActionRow(
                    title = rule.name,
                    summary = rule.urlRule,
                    modifier = Modifier.testTag("dictionary-rule:${rule.name}")
                        .clickable(enabled = active) { controller.openDictionaryRuleEditor(rule) },
                ) {
                    Switch(
                        checked = rule.enabled,
                        enabled = active,
                        onCheckedChange = { controller.setDictionaryRuleEnabled(rule, it) },
                        modifier = Modifier.testTag("dictionary-rule-toggle:${rule.name}"),
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton({ controller.deleteDictionaryRule(rule) }, enabled = active,
                        modifier = Modifier.testTag("dictionary-rule-delete:${rule.name}")) {
                        Text(AndroidStrings["delete"])
                    }
                }
            }
        }
    }
    if (ui.editorOpen) {
        DictionaryRuleEditorDialog(
            initial = ui.editing,
            onDismiss = { controller.closeDictionaryRuleEditor() },
            onSave = { controller.saveDictionaryRule(it) },
            onDelete = ui.editing?.let { rule -> { controller.deleteDictionaryRule(rule) } },
        )
    }
}

@Composable
private fun DictionaryRuleEditorDialog(
    initial: DictionaryRule?,
    onDismiss: () -> Unit,
    onSave: (DictionaryRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    // Android keys the draft on the rule's name, so opening another rule starts a fresh draft.
    var name by remember(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }
    var urlRule by remember(initial?.name) { mutableStateOf(initial?.urlRule.orEmpty()) }
    var showRule by remember(initial?.name) { mutableStateOf(initial?.showRule.orEmpty()) }
    var sortNumber by remember(initial?.name) { mutableStateOf(initial?.sortNumber?.toString().orEmpty()) }
    var enabled by remember(initial?.name) { mutableStateOf(initial?.enabled ?: true) }
    val candidate = DictionaryRule(
        name = name.trim(),
        urlRule = urlRule.trim(),
        showRule = showRule,
        enabled = enabled,
        sortNumber = sortNumber.trim().toIntOrNull() ?: 0,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) {
                    AndroidStrings["dictionary_rule_add_title"]
                } else {
                    AndroidStrings["dictionary_rule_edit_title"]
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DictionaryRuleField(name, { name = it }, AndroidStrings["name"], "dictionary-rule-name")
                DictionaryRuleField(
                    urlRule, { urlRule = it }, AndroidStrings["dictionary_rule_url_label"], "dictionary-rule-url",
                    minLines = 2,
                )
                DictionaryRuleField(
                    showRule, { showRule = it }, AndroidStrings["dictionary_rule_show_label"], "dictionary-rule-show",
                    minLines = 2, monospace = true,
                )
                DictionaryRuleField(
                    sortNumber, { sortNumber = it }, AndroidStrings["sort"], "dictionary-rule-sort",
                )
                Row(
                    Modifier.fillMaxWidth().clickable { enabled = !enabled }
                        .testTag("dictionary-rule-enabled"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = enabled, onCheckedChange = null)
                    Text(AndroidStrings["enable"], modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        // Android only enables save once both the name and the URL rule are there, the name being the primary key.
        confirmButton = {
            TextButton({ onSave(candidate) }, enabled = name.isNotBlank() && urlRule.isNotBlank(),
                modifier = Modifier.testTag("dictionary-rule-save")) { Text(AndroidStrings["save"]) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onDelete, modifier = Modifier.testTag("dictionary-rule-remove")) {
                        Text(AndroidStrings["delete"])
                    }
                }
                TextButton(onDismiss, modifier = Modifier.testTag("dictionary-rule-cancel")) {
                    Text(AndroidStrings["cancel"])
                }
            }
        },
    )
}

@Composable
private fun DictionaryRuleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String,
    minLines: Int = 1,
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        maxLines = if (minLines > 1) 6 else 1,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    )
}

/** Android's SAF create/open document, replaced by the host's file chooser; the codec and file name are shared. */
private fun importDictionaryRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("dictionaryRules.filter"), "json")
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.importDictionaryRules(chooser.selectedFile.toPath())
    }
}

private fun exportDictionaryRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("dictionaryRules.filter"), "json")
        selectedFile = File("kototoro-dictionary-rules.json")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.exportDictionaryRules(chooser.selectedFile.toPath())
    }
}
