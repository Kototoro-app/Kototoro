package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.ui.settings.ReplaceRuleEditorDialog
import org.skepsun.kototoro.core.ui.settings.ReplaceRuleUiModel
import org.skepsun.kototoro.core.ui.settings.ReplaceRulesContent

/**
 * Android's replace-rule settings page. The action group, the grouped rule list and the editor dialog now come from the
 * shared [ReplaceRulesContent]/[ReplaceRuleEditorDialog]; this screen keeps the settings inset, the rule table lookup and
 * the success semantics (Android closes the editor as soon as it files the write, desktop after the write lands).
 */
@Composable
fun ReplaceRulesSettingsScreen(
    rules: List<ReplaceRule>,
    onToggle: (ReplaceRule, Boolean) -> Unit,
    onSetAllEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSave: (ReplaceRule) -> Unit,
    onDelete: (ReplaceRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingRule by remember { mutableStateOf<ReplaceRule?>(null) }
    var isEditorVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = settingsContentTopInset(),
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                bottom = 24.dp,
            ),
    ) {
        ReplaceRulesContent(
            rules = rules.map(ReplaceRule::toUiModel),
            onAdd = {
                editingRule = null
                isEditorVisible = true
            },
            onEdit = { item ->
                editingRule = rules.firstOrNull { it.id == item.id }
                isEditorVisible = true
            },
            onToggle = { item, enabled ->
                rules.firstOrNull { it.id == item.id }?.let { onToggle(it, enabled) }
            },
            onSetAllEnabled = onSetAllEnabled,
            onImport = onImport,
            onExport = onExport,
        )
    }

    if (isEditorVisible) {
        ReplaceRuleEditorDialog(
            initial = editingRule?.toUiModel(),
            onDismiss = { isEditorVisible = false },
            onSave = { item ->
                onSave(item.toRule())
                isEditorVisible = false
            },
            // Validation and preview are the stored rule's own semantics, so both hosts answer them from the entity.
            isValid = { it.toRule().isValid() },
            preview = { item, text -> item.toRule().apply(text) },
            onDelete = editingRule?.let { rule ->
                {
                    onDelete(rule)
                    isEditorVisible = false
                }
            },
        )
    }
}

/** The stored row as the shared page shows it; the nullable columns become blank strings. */
private fun ReplaceRule.toUiModel() = ReplaceRuleUiModel(
    id = id,
    name = name,
    group = group.orEmpty(),
    pattern = pattern,
    replacement = replacement,
    scopeFilter = scopeFilter.orEmpty(),
    excludeScope = excludeScope.orEmpty(),
    isRegex = isRegex,
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    isEnabled = isEnabled,
    order = order,
    timeoutMillisecond = timeoutMillisecond,
)

/** Back to the entity the repository stores, keeping the blank-to-null mapping for the optional columns. */
private fun ReplaceRuleUiModel.toRule() = ReplaceRule(
    id = id,
    name = name,
    group = group.ifBlank { null },
    pattern = pattern,
    replacement = replacement,
    scopeFilter = scopeFilter.ifBlank { null },
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    excludeScope = excludeScope.ifBlank { null },
    isEnabled = isEnabled,
    isRegex = isRegex,
    timeoutMillisecond = timeoutMillisecond,
    order = order,
)
