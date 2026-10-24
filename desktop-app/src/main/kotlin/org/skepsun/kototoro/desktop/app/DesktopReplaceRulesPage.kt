package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Android's replace-rules settings screen for the Windows host.
 *
 * Android shows the actions in one group and the rules — grouped by their group name — below it, and edits a rule in a
 * dialog that carries a live preview; this page keeps that shape on the shared settings rows, with the import/export
 * actions going through the same JSON codec the Android SAF flow uses.
 */
@Composable
internal fun DesktopReplaceRulesPage(controller: DesktopController, state: DesktopAppState) {
    val ui by controller.replaceRules.collectAsState()
    val active = !state.busy
    val rules = ui.rules
    Column(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.backFromReplaceRules() }, enabled = active,
                modifier = Modifier.testTag("replace-rules-back")) { Text(desktopText("common.back")) }
            Spacer(Modifier.width(12.dp))
            Text(AndroidStrings["replace_rules"], style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton({ importReplaceRules(controller) }, enabled = active,
                modifier = Modifier.testTag("replace-rules-import")) { Text(AndroidStrings["replace_rule_import"]) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ exportReplaceRules(controller) }, enabled = active && !rules.isNullOrEmpty(),
                modifier = Modifier.testTag("replace-rules-export")) { Text(AndroidStrings["replace_rule_export"]) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ controller.openReplaceRuleEditor(null) }, enabled = active,
                modifier = Modifier.testTag("replace-rules-add")) { Text(AndroidStrings["replace_rule_add"]) }
        }
        Text(AndroidStrings["replace_rules_summary"], style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.testTag("replace-rules-summary"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ controller.setAllReplaceRulesEnabled(true) },
                enabled = active && rules.orEmpty().any { !it.isEnabled },
                modifier = Modifier.testTag("replace-rules-enable-all")) {
                Text(AndroidStrings["replace_rule_enable_all"])
            }
            OutlinedButton({ controller.setAllReplaceRulesEnabled(false) },
                enabled = active && rules.orEmpty().any(ReplaceRule::isEnabled),
                modifier = Modifier.testTag("replace-rules-disable-all")) {
                Text(AndroidStrings["replace_rule_disable_all"])
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            // Null means the table has not been read yet: an empty list is a real answer, "not asked" is not.
            rules == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(desktopText("replaceRules.loading"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("replace-rules-loading"))
            }

            rules.isEmpty() -> SettingsInfoRow(AndroidStrings["replace_rule_empty"],
                AndroidStrings["replace_rule_empty_summary"], Modifier.testTag("replace-rules-empty"))

            else -> ReplaceRuleGroups(rules, active, controller)
        }
    }
    if (ui.editorOpen) {
        ReplaceRuleEditorDialog(
            initial = ui.editing,
            onDismiss = { controller.closeReplaceRuleEditor() },
            onSave = { controller.saveReplaceRule(it) },
            onDelete = ui.editing?.let { rule -> { controller.deleteReplaceRule(rule) } },
        )
    }
}

/** Android's `ruleSummary`: what the rule matches, what it draws, and where it applies. */
@Composable
private fun ReplaceRuleGroups(rules: List<ReplaceRule>, enabled: Boolean, controller: DesktopController) {
    val groups = rules.groupBy { it.group?.trim().orEmpty() }
    groups.forEach { (group, groupRules) ->
        if (groups.size > 1 || group.isNotBlank()) {
            SettingsSectionHeader(group.ifBlank { AndroidStrings["replace_rule_group_default"] })
        }
        groupRules.forEachIndexed { index, rule ->
            if (index > 0) SettingsRowDivider()
            SettingsActionRow(
                title = rule.name.ifBlank { rule.pattern },
                summary = replaceRuleSummary(rule),
                modifier = Modifier.testTag("replace-rule:${rule.id}").clickable(enabled = enabled) {
                    controller.openReplaceRuleEditor(rule)
                },
            ) {
                Switch(checked = rule.isEnabled, enabled = enabled, onCheckedChange = { controller.setReplaceRuleEnabled(rule, it) },
                    modifier = Modifier.testTag("replace-rule-toggle:${rule.id}"))
            }
        }
    }
}

@Composable
private fun replaceRuleSummary(rule: ReplaceRule): String {
    val scope = when {
        rule.scopeTitle && rule.scopeContent -> desktopText("replaceRules.scopeBoth")
        rule.scopeTitle -> desktopText("replaceRules.scopeTitle")
        else -> desktopText("replaceRules.scopeContent")
    }
    val mode = if (rule.isRegex) desktopText("replaceRules.regexMode") else desktopText("replaceRules.textMode")
    return "$mode · $scope · ${rule.pattern} → ${rule.replacement}"
}

/**
 * Android's `ReplaceRuleEditorDialog`: every stored field, the validity warning it derives, and the live preview that
 * lets a user see a pattern work before saving it. The dialog keeps a draft; [onSave] receives the rule built from it.
 */
@Composable
private fun ReplaceRuleEditorDialog(
    initial: ReplaceRule?,
    onDismiss: () -> Unit,
    onSave: (ReplaceRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val base = remember(initial?.id) {
        initial ?: ReplaceRule(name = "", isRegex = true, scopeContent = true, order = 0)
    }
    var name by remember(base.id) { mutableStateOf(base.name) }
    var group by remember(base.id) { mutableStateOf(base.group.orEmpty()) }
    var pattern by remember(base.id) { mutableStateOf(base.pattern) }
    var replacement by remember(base.id) { mutableStateOf(base.replacement) }
    var scopeFilter by remember(base.id) { mutableStateOf(base.scopeFilter.orEmpty()) }
    var excludeScope by remember(base.id) { mutableStateOf(base.excludeScope.orEmpty()) }
    var isRegex by remember(base.id) { mutableStateOf(base.isRegex) }
    var scopeTitle by remember(base.id) { mutableStateOf(base.scopeTitle) }
    var scopeContent by remember(base.id) { mutableStateOf(base.scopeContent) }
    var isEnabled by remember(base.id) { mutableStateOf(base.isEnabled) }
    var order by remember(base.id) { mutableStateOf(base.order.toString()) }
    var timeout by remember(base.id) { mutableStateOf(base.timeoutMillisecond.toString()) }
    var previewInput by remember(base.id) { mutableStateOf("") }
    val candidate = base.copy(
        name = name,
        group = group.ifBlank { null },
        pattern = pattern,
        replacement = replacement,
        scopeFilter = scopeFilter.ifBlank { null },
        excludeScope = excludeScope.ifBlank { null },
        isRegex = isRegex,
        scopeTitle = scopeTitle,
        scopeContent = scopeContent,
        isEnabled = isEnabled,
        order = order.toIntOrNull() ?: 0,
        timeoutMillisecond = timeout.toLongOrNull()?.coerceAtLeast(0L) ?: base.timeoutMillisecond,
    )
    val isValid = candidate.isValid() && (scopeTitle || scopeContent)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AndroidStrings[if (initial == null) "replace_rule_add" else "replace_rule_edit"]) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuleField(name, AndroidStrings["replace_rule_name"], { name = it }, "replace-rule-name")
                RuleField(group, AndroidStrings["replace_rule_group"], { group = it }, "replace-rule-group")
                RuleField(pattern, AndroidStrings["replace_rule_pattern"], { pattern = it }, "replace-rule-pattern",
                    minLines = 1, maxLines = 3, monospace = true)
                RuleField(replacement, AndroidStrings["replace_rule_replacement"], { replacement = it },
                    "replace-rule-replacement", minLines = 1, maxLines = 3, monospace = true)
                RuleField(scopeFilter, AndroidStrings["replace_rule_scope"], { scopeFilter = it }, "replace-rule-scope",
                    supportingText = AndroidStrings["replace_rule_scope_hint"])
                RuleField(excludeScope, AndroidStrings["replace_rule_exclude_scope"], { excludeScope = it },
                    "replace-rule-exclude")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleField(order, AndroidStrings["replace_rule_order"], { order = it }, "replace-rule-order",
                        modifier = Modifier.weight(1f))
                    RuleField(timeout, AndroidStrings["replace_rule_timeout"], { timeout = it }, "replace-rule-timeout",
                        modifier = Modifier.weight(1f))
                }
                RuleToggle(isRegex, AndroidStrings["replace_rule_regex"], "replace-rule-regex") { isRegex = it }
                RuleToggle(isEnabled, desktopText("replaceRules.enabled"), "replace-rule-enabled") { isEnabled = it }
                RuleToggle(scopeTitle, AndroidStrings["replace_rule_scope_title"], "replace-rule-scope-title") {
                    scopeTitle = it
                }
                RuleToggle(scopeContent, AndroidStrings["replace_rule_scope_content"], "replace-rule-scope-content") {
                    scopeContent = it
                }
                if (pattern.isNotBlank() && !candidate.isValid()) {
                    Text(AndroidStrings["replace_rule_invalid"], color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("replace-rule-invalid"))
                }
                if (pattern.isNotBlank()) {
                    Text(AndroidStrings["replace_rule_preview"], style = MaterialTheme.typography.titleSmall)
                    RuleField(previewInput, AndroidStrings["replace_rule_preview_input"], { previewInput = it },
                        "replace-rule-preview-input", minLines = 2, maxLines = 4,
                        supportingText = AndroidStrings["replace_rule_preview_hint"])
                    if (previewInput.isNotEmpty()) {
                        OutlinedTextField(
                            value = candidate.apply(previewInput),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(AndroidStrings["replace_rule_preview_output"]) },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth().testTag("replace-rule-preview-output"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onSave(candidate) }, enabled = isValid,
                modifier = Modifier.testTag("replace-rule-save")) { Text(AndroidStrings["save"]) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onDelete, modifier = Modifier.testTag("replace-rule-delete")) {
                        Text(AndroidStrings["delete"])
                    }
                }
                TextButton(onDismiss, modifier = Modifier.testTag("replace-rule-cancel")) {
                    Text(AndroidStrings["cancel"])
                }
            }
        },
    )
}

@Composable
private fun RuleField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        modifier = modifier.fillMaxWidth().testTag(testTag),
    )
}

@Composable
private fun RuleToggle(checked: Boolean, text: String, testTag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.testTag(testTag).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = null)
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Android's SAF create/open document, replaced by the host's file chooser; the codec and file name are shared. */
private fun importReplaceRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("replaceRules.filter"), "json")
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.importReplaceRules(chooser.selectedFile.toPath())
    }
}

private fun exportReplaceRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("replaceRules.filter"), "json")
        selectedFile = File("kototoro-replace-rules.json")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.exportReplaceRules(chooser.selectedFile.toPath())
    }
}
