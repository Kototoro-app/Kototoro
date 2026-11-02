package org.skepsun.kototoro.core.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputEnterAction
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.settings.compose.LocalSettingsDialogRenderer
import org.skepsun.kototoro.settings.compose.SettingsActionPreference
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.settings.compose.SettingsInfoPreference
import org.skepsun.kototoro.settings.compose.SettingsPreferenceGroup
import org.skepsun.kototoro.settings.compose.SettingsSplitSwitchPreference

/**
 * UI values only: the hosts keep the stored rule (Room entity), its validation and its preview application, so the
 * shared page does not grow a dependency on the database module. `""` stands for the nullable `group`, `scopeFilter`
 * and `excludeScope` columns; hosts map it back to `null` when saving, like Android always did.
 */
data class ReplaceRuleUiModel(
    val id: Long = 0L,
    val name: String = "",
    val group: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scopeFilter: String = "",
    val excludeScope: String = "",
    val isRegex: Boolean = true,
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val isEnabled: Boolean = true,
    val order: Int = 0,
    val timeoutMillisecond: Long = 3000L,
)

/**
 * Android's `ReplaceRulesSettingsScreen`: the action group, then the stored rules grouped by their group name.
 *
 * [rules] is null while the host is still reading the table — a desktop-only state, because Android's flow starts from
 * an empty list — and an empty list is a real answer that shows Android's empty row. The host owns adding, importing and
 * exporting (SAF on Android, a file chooser on desktop) and opening the editor; this page only projects the list and
 * forwards the toggles.
 */
@Composable
fun ReplaceRulesContent(
    rules: List<ReplaceRuleUiModel>?,
    onAdd: () -> Unit,
    onEdit: (ReplaceRuleUiModel) -> Unit,
    onToggle: (ReplaceRuleUiModel, Boolean) -> Unit,
    onSetAllEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loadingText: String = uiString("loading_"),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsPreferenceGroup(title = uiString("replace_rules_actions")) {
            item {
                SettingsActionPreference(
                    title = uiString("replace_rule_add"),
                    summary = uiString("replace_rule_add_summary"),
                    icon = rememberVectorPainter(ReaderPanelIcons.BookPage),
                    enabled = enabled,
                    modifier = Modifier.testTag("replace-rules-add"),
                    onClick = onAdd,
                )
            }
            item {
                SettingsActionPreference(
                    title = uiString("replace_rule_enable_all"),
                    summary = uiString("replace_rule_enable_all_summary"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Check),
                    enabled = enabled && rules.orEmpty().any { !it.isEnabled },
                    modifier = Modifier.testTag("replace-rules-enable-all"),
                    onClick = { onSetAllEnabled(true) },
                )
            }
            item {
                SettingsActionPreference(
                    title = uiString("replace_rule_disable_all"),
                    summary = uiString("replace_rule_disable_all_summary"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Disable),
                    enabled = enabled && rules.orEmpty().any { it.isEnabled },
                    modifier = Modifier.testTag("replace-rules-disable-all"),
                    onClick = { onSetAllEnabled(false) },
                )
            }
            item {
                SettingsActionPreference(
                    title = uiString("replace_rule_import"),
                    summary = uiString("replace_rule_import_summary"),
                    icon = rememberVectorPainter(ReaderPanelIcons.Import),
                    enabled = enabled,
                    modifier = Modifier.testTag("replace-rules-import"),
                    onClick = onImport,
                )
            }
            item {
                SettingsActionPreference(
                    title = uiString("replace_rule_export"),
                    summary = uiString("replace_rule_export_summary"),
                    icon = rememberVectorPainter(ReaderPanelIcons.CloudUpload),
                    // Nothing to write yet: the same greyed-out action the desktop page had before the page was shared.
                    enabled = enabled && !rules.isNullOrEmpty(),
                    modifier = Modifier.testTag("replace-rules-export"),
                    onClick = onExport,
                )
            }
        }
        SettingsPreferenceGroup(title = uiString("replace_rules")) {
            when {
                rules == null -> item {
                    ReplaceRulesLoadingRow(loadingText)
                }
                rules.isEmpty() -> item {
                    SettingsInfoPreference(
                        title = uiString("replace_rule_empty"),
                        summary = uiString("replace_rule_empty_summary"),
                        modifier = Modifier.testTag("replace-rules-empty"),
                    )
                }
                else -> {
                    // Android groups by the trimmed group name; a blank name is the default group.
                    val groups = rules.groupBy { it.group.trim() }
                    groups.forEach { (group, groupRules) ->
                        if (groups.size > 1 || group.isNotBlank()) {
                            item {
                                Text(
                                    text = group.ifBlank { uiString("replace_rule_group_default") },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        groupRules.forEach { rule ->
                            item {
                                SettingsSplitSwitchPreference(
                                    title = rule.name.ifBlank { rule.pattern },
                                    summary = replaceRuleSummary(rule),
                                    checked = rule.isEnabled,
                                    icon = rememberVectorPainter(ReaderPanelIcons.ReorderHandle),
                                    enabled = enabled,
                                    modifier = Modifier.testTag("replace-rule:${rule.id}"),
                                    switchModifier = Modifier.testTag("replace-rule-toggle:${rule.id}"),
                                    onClick = { onEdit(rule) },
                                    onCheckedChange = { onToggle(rule, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Android's rule summary: `mode · scope · pattern → replacement`. */
@Composable
private fun replaceRuleSummary(rule: ReplaceRuleUiModel): String {
    val mode = uiString(if (rule.isRegex) "replace_rule_mode_regex" else "replace_rule_mode_text")
    val scope = uiString(
        when {
            rule.scopeTitle && rule.scopeContent -> "novel_reader_replace_scope_title_content"
            rule.scopeTitle -> "novel_reader_replace_scope_title"
            else -> "novel_reader_replace_scope_content"
        },
    )
    return "$mode · $scope · ${rule.pattern} → ${rule.replacement}"
}

@Composable
private fun ReplaceRulesLoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("replace-rules-loading")
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Android's `ReplaceRuleEditorDialog`: every stored field, the validity warning it derives, and the live preview that
 * lets a user see a pattern work before saving it. The dialog keeps a draft, so [onSave] receives the rule built from
 * it; the host decides when a successful save closes the editor.
 *
 * [isValid] and [preview] arrive from the host because they are `ReplaceRule`'s own semantics (regex compilation,
 * the disabled-rule short circuit); this dialog never re-implements them.
 */
@Composable
fun ReplaceRuleEditorDialog(
    initial: ReplaceRuleUiModel?,
    onDismiss: () -> Unit,
    onSave: (ReplaceRuleUiModel) -> Unit,
    isValid: (ReplaceRuleUiModel) -> Boolean,
    preview: (ReplaceRuleUiModel, String) -> String,
    onDelete: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val base = remember(initial?.id) {
        initial ?: ReplaceRuleUiModel()
    }
    var name by remember(base.id) { mutableStateOf(base.name) }
    var group by remember(base.id) { mutableStateOf(base.group) }
    var pattern by remember(base.id) { mutableStateOf(base.pattern) }
    var replacement by remember(base.id) { mutableStateOf(base.replacement) }
    var scopeFilter by remember(base.id) { mutableStateOf(base.scopeFilter) }
    var excludeScope by remember(base.id) { mutableStateOf(base.excludeScope) }
    var isRegex by remember(base.id) { mutableStateOf(base.isRegex) }
    var scopeTitle by remember(base.id) { mutableStateOf(base.scopeTitle) }
    var scopeContent by remember(base.id) { mutableStateOf(base.scopeContent) }
    var order by remember(base.id) { mutableStateOf(base.order.toString()) }
    var timeout by remember(base.id) { mutableStateOf(base.timeoutMillisecond.toString()) }
    var previewInput by remember(base.id) { mutableStateOf("") }

    val candidate = base.copy(
        name = name,
        group = group,
        pattern = pattern,
        replacement = replacement,
        scopeFilter = scopeFilter,
        excludeScope = excludeScope,
        isRegex = isRegex,
        scopeTitle = scopeTitle,
        scopeContent = scopeContent,
        order = order.toIntOrNull() ?: 0,
        timeoutMillisecond = timeout.toLongOrNull()?.coerceAtLeast(0L) ?: base.timeoutMillisecond,
    )
    val candidateValid = isValid(candidate) && (scopeTitle || scopeContent)
    val isTv = LocalUiPresentationConfig.current.isTv
    val keyboardController = LocalSoftwareKeyboardController.current
    val saveFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    // A remote cannot leave a rule field: the IME owns the D-pad while it is open, predictive back never reaches a key
    // handler, and Back dismisses the whole editor. The IME's own Done action (plus the button below the fields) moves
    // focus onto the dialog actions instead.
    val leaveFields: () -> Unit = {
        keyboardController?.hide()
        if (candidateValid) saveFocusRequester.requestFocus() else cancelFocusRequester.requestFocus()
    }
    LocalSettingsDialogRenderer.current(
        PreferenceDialogContent(
            title = uiString(if (initial == null) "replace_rule_add" else "replace_rule_edit"),
            onDismiss = onDismiss,
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ReplaceRuleField(name, uiString("replace_rule_name"), { name = it }, "replace-rule-name",
                        enabled = enabled, onImeDone = leaveFields)
                    ReplaceRuleField(group, uiString("replace_rule_group"), { group = it }, "replace-rule-group",
                        enabled = enabled, onImeDone = leaveFields)
                    ReplaceRuleField(pattern, uiString("replace_rule_pattern"), { pattern = it },
                        "replace-rule-pattern", enabled = enabled,
                        isError = pattern.isNotBlank() && !candidateValid, onImeDone = leaveFields)
                    ReplaceRuleField(replacement, uiString("replace_rule_replacement"), { replacement = it },
                        "replace-rule-replacement", enabled = enabled, minLines = 1, maxLines = 3,
                        onImeDone = leaveFields)
                    ReplaceRuleField(scopeFilter, uiString("replace_rule_scope"), { scopeFilter = it },
                        "replace-rule-scope", enabled = enabled,
                        supportingText = uiString("replace_rule_scope_hint"), onImeDone = leaveFields)
                    ReplaceRuleField(excludeScope, uiString("replace_rule_exclude_scope"), { excludeScope = it },
                        "replace-rule-exclude", enabled = enabled,
                        supportingText = uiString("replace_rule_scope_hint"), onImeDone = leaveFields)
                    ReplaceRuleField(order, uiString("replace_rule_order"),
                        { order = it.filter(Char::isDigit).take(8) }, "replace-rule-order",
                        enabled = enabled, onImeDone = leaveFields)
                    ReplaceRuleField(timeout, uiString("replace_rule_timeout"),
                        { timeout = it.filter(Char::isDigit).take(8) }, "replace-rule-timeout",
                        enabled = enabled, onImeDone = leaveFields)
                    ReplaceRuleCheckRow(isRegex, uiString("replace_rule_regex"), "replace-rule-regex",
                        enabled = enabled) { isRegex = it }
                    ReplaceRuleCheckRow(scopeTitle, uiString("replace_rule_scope_title"), "replace-rule-scope-title",
                        enabled = enabled) { scopeTitle = it }
                    ReplaceRuleCheckRow(scopeContent, uiString("replace_rule_scope_content"), "replace-rule-scope-content",
                        enabled = enabled) { scopeContent = it }
                    if (pattern.isNotBlank() && !candidateValid) {
                        Text(
                            text = uiString("replace_rule_invalid"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("replace-rule-invalid"),
                        )
                    }
                    if (pattern.isNotBlank()) {
                        Text(uiString("replace_rule_preview"), style = MaterialTheme.typography.titleSmall)
                        ReplaceRuleField(previewInput, uiString("replace_rule_preview_input"),
                            { previewInput = it }, "replace-rule-preview-input",
                            enabled = enabled, minLines = 2, maxLines = 5,
                            supportingText = uiString("replace_rule_preview_hint"))
                        if (previewInput.isNotEmpty()) {
                            ReplaceRuleField(preview(candidate, previewInput), uiString("replace_rule_preview_output"),
                                {}, "replace-rule-preview-output", enabled = enabled, readOnly = true,
                                minLines = 2, maxLines = 5)
                        }
                    }
                    if (isTv) {
                        TextButton(
                            onClick = leaveFields,
                            modifier = Modifier.tvFocusable(
                                enabled = true,
                                shape = RoundedCornerShape(10.dp),
                                addFocusTarget = false,
                            ),
                        ) {
                            Text(uiString("done"))
                        }
                    }
                }
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = uiString("save"),
                    enabled = enabled && candidateValid,
                    onClick = { onSave(candidate) },
                    modifier = Modifier.focusRequester(saveFocusRequester).testTag("replace-rule-save"),
                )
            },
            dismissButton = {
                Row {
                    if (onDelete != null) {
                        SettingsDialogActionButton(
                            text = uiString("delete"),
                            enabled = enabled,
                            onClick = onDelete,
                            modifier = Modifier.testTag("replace-rule-delete"),
                        )
                    }
                    SettingsDialogActionButton(
                        text = uiString("cancel"),
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocusRequester).testTag("replace-rule-cancel"),
                    )
                }
            },
        ),
    )
}

@Composable
private fun ReplaceRuleField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    tag: String,
    enabled: Boolean,
    supportingText: String? = null,
    isError: Boolean = false,
    readOnly: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    onImeDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onImeDone?.invoke() }),
        label = { Text(label) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        isError = isError,
        readOnly = readOnly,
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .then(if (onImeDone != null) Modifier.tvTextInputEnterAction(onImeDone) else Modifier),
    )
}

@Composable
private fun ReplaceRuleCheckRow(
    checked: Boolean,
    text: String,
    tag: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}
