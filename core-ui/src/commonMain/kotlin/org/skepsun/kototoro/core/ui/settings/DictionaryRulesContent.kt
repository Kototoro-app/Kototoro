package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

/** UI values only: hosts retain the dictionary table and parsing of numeric input. */
data class DictionaryRuleUiModel(
    val name: String,
    val urlRule: String,
    val showRule: String = "",
    val enabled: Boolean = true,
    val sortNumber: String = "0",
)

/** Android's rule cards, including loading/empty states for hosts that load the table asynchronously. */
@Composable
fun DictionaryRulesContent(
    rules: List<DictionaryRuleUiModel>?,
    onEdit: (DictionaryRuleUiModel) -> Unit,
    onToggle: (DictionaryRuleUiModel, Boolean) -> Unit,
    onDelete: (DictionaryRuleUiModel) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loadingText: String = uiString("loading_"),
) {
    when {
        rules == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(loadingText, Modifier.testTag("dictionary-rules-loading"))
            }
        }
        rules.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(uiString("dictionary_rules_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("dictionary-rules-empty"))
        }
        else -> LazyColumn(modifier.fillMaxSize().testTag("dictionary-rules-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.name }) { rule ->
                Card(onClick = { onEdit(rule) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("dictionary-rule:${rule.name}")) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Text(rule.urlRule, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(rule.enabled, { onToggle(rule, it) }, enabled = enabled,
                            modifier = Modifier.testTag("dictionary-rule-toggle:${rule.name}"))
                        IconButton({ onDelete(rule) }, enabled = enabled,
                            modifier = Modifier.tvIconButtonRing().testTag("dictionary-rule-delete:${rule.name}")) {
                            Icon(ReaderPanelIcons.Delete, contentDescription = uiString("delete"))
                        }
                    }
                }
            }
        }
    }
}

/** One draft and validation path; the host decides when a successful save closes the editor. */
@Composable
fun DictionaryRuleEditorDialog(
    initial: DictionaryRuleUiModel?,
    onDismiss: () -> Unit,
    onSave: (DictionaryRuleUiModel) -> Unit,
    onDelete: (() -> Unit)? = null,
    enabled: Boolean = true,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var draft by remember(initial?.name) { mutableStateOf(initial ?: DictionaryRuleUiModel("", "")) }
    renderDialog(PreferenceDialogContent(
        title = if (initial == null) uiString("dictionary_rule_add_title") else uiString("dictionary_rule_edit_title"),
        onDismiss = onDismiss,
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DictionaryRuleField(draft.name, { draft = draft.copy(name = it) }, uiString("name"),
                    "dictionary-rule-name", enabled = enabled)
                DictionaryRuleField(draft.urlRule, { draft = draft.copy(urlRule = it) },
                    uiString("dictionary_rule_url_label"), "dictionary-rule-url", minLines = 2, enabled = enabled)
                DictionaryRuleField(draft.showRule, { draft = draft.copy(showRule = it) },
                    uiString("dictionary_rule_show_label"), "dictionary-rule-show", minLines = 2,
                    monospace = true, enabled = enabled)
                DictionaryRuleField(draft.sortNumber, { draft = draft.copy(sortNumber = it) }, uiString("sort"),
                    "dictionary-rule-sort", enabled = enabled)
                Row(Modifier.fillMaxWidth().toggleable(draft.enabled, enabled = enabled, role = Role.Switch) {
                    draft = draft.copy(enabled = it)
                }.testTag("dictionary-rule-enabled"), verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.enabled, onCheckedChange = null, enabled = enabled)
                    Text(uiString("enable"), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            SettingsDialogActionButton(uiString("save"), { onSave(draft.copy(name = draft.name.trim(),
                urlRule = draft.urlRule.trim())) }, enabled = enabled && draft.name.isNotBlank() && draft.urlRule.isNotBlank(),
                modifier = Modifier.testTag("dictionary-rule-save"))
        },
        dismissButton = {
            Row {
                if (onDelete != null) SettingsDialogActionButton(uiString("delete"), onDelete, enabled = enabled,
                    modifier = Modifier.testTag("dictionary-rule-remove"))
                SettingsDialogActionButton(uiString("cancel"), onDismiss,
                    modifier = Modifier.testTag("dictionary-rule-cancel"))
            }
        },
    ))
}

@Composable
private fun DictionaryRuleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean,
    minLines: Int = 1,
    monospace: Boolean = false,
) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, enabled = enabled,
        singleLine = minLines == 1, minLines = minLines, maxLines = if (minLines > 1) 6 else 1,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (monospace) FontFamily.Monospace else null),
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .then(if (minLines == 1) Modifier.tvTextInputFocusEscape() else Modifier))
}
