package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.replace.ReplaceRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeNovelReplaceRulesSheet(
    rules: List<ReplaceRule>,
    disabledRuleIds: Set<Long> = emptySet(),
    scopeName: String,
    origin: String,
    onDismiss: () -> Unit,
    onToggle: (ReplaceRule, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val effectiveRules = remember(rules, scopeName, origin) {
        val scopeValues = listOf(scopeName, origin)
        rules.filter { rule ->
            rule.isValid() && (
                rule.appliesTo(ReplaceRule.Scope.CONTENT, scopeValues) ||
                    rule.appliesTo(ReplaceRule.Scope.TITLE, scopeValues)
                )
        }
    }
    val visibleRules = remember(effectiveRules, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            effectiveRules
        } else {
            effectiveRules.filter { rule ->
                listOf(rule.name, rule.group.orEmpty(), rule.pattern, rule.replacement)
                    .any { it.contains(normalizedQuery, ignoreCase = true) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.replace_rule_effective_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.replace_rule_effective_summary, effectiveRules.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.replace_rule_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            ) {
                if (visibleRules.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.replace_rule_effective_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(visibleRules, key = { it.id }) { rule ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = rule.name.ifBlank { rule.pattern },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = ruleSummary(rule),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = rule.isEnabled && rule.id !in disabledRuleIds,
                                    enabled = rule.isEnabled,
                                    onCheckedChange = { onToggle(rule, it) },
                                )
                            },
                            modifier = Modifier.clickable(
                                enabled = rule.isEnabled,
                                onClick = { onToggle(rule, !rule.isEnabled) },
                            ),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ruleSummary(rule: ReplaceRule): String {
    val target = when {
        rule.scopeTitle && rule.scopeContent -> stringResource(R.string.novel_reader_replace_scope_title_content)
        rule.scopeTitle -> stringResource(R.string.novel_reader_replace_scope_title)
        else -> stringResource(R.string.novel_reader_replace_scope_content)
    }
    val replacement = rule.replacement.ifBlank { "∅" }
    return "$target · ${rule.pattern} → $replacement"
}
