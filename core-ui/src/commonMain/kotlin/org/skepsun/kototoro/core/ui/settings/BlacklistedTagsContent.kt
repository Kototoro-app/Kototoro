package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.settings.compose.SettingsMaterialIcons

/**
 * One row of the blacklist catalogue: the stored [key] (a taxonomy id or a `raw:` pattern), the label to show, the
 * aliases to reveal, and the category the catalogue files it under. The taxonomy itself — the ids, the alias index and
 * the matcher — is shared, so both hosts only decide how a row is drawn.
 */
data class BlacklistTagUiModel(
    val key: String,
    val label: String,
    val aliases: List<String> = emptyList(),
    val categoryId: String,
    val categoryName: String,
)

/**
 * Android's global tag blacklist page without its top bar: the search field, the available/selected counters, the
 * catalogue grouped by category and the "add this pattern" action for a title the catalogue cannot resolve. Both hosts
 * draw the same grid, so a tag hides the same works whether it was blacklisted on a phone or on Windows.
 *
 * The details dialog stays with the host: Android shows the aliases, the desktop page also shows the key, the English
 * label and the category, and a phone has no room for that. [header] and [footer] let a host keep its own rows (a back
 * button, a note) inside the same scroll container, so both stay scrollable without nesting two scrollers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlacklistedTagsContent(
    available: List<BlacklistTagUiModel>,
    selected: List<BlacklistTagUiModel>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onShowDetails: (BlacklistTagUiModel) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rawPatternLabel: String? = null,
    onAddRawPattern: (() -> Unit)? = null,
    searchModifier: Modifier = Modifier,
    tagPrefix: String = "blacklist",
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val selectedKeys = selected.mapTo(HashSet()) { it.key }
    val emptyText = uiString("nothing_found")
    val availableLabel = uiString("available")
    val selectedLabel = uiString("selected_count", selected.size)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxSize().testTag("$tagPrefix-grid"),
    ) {
        header?.let { headerContent ->
            item(key = "$tagPrefix-header", span = { GridItemSpan(maxLineSpan) }) { headerContent() }
        }
        item(key = "$tagPrefix-search", span = { GridItemSpan(maxLineSpan) }) {
            SettingsSearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                enabled = enabled,
                inputModifier = searchModifier,
            )
        }
        item(key = "$tagPrefix-counters", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(availableLabel, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("$tagPrefix-available"))
                Text(selectedLabel, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("$tagPrefix-selected-count"))
            }
        }
        if (rawPatternLabel != null && onAddRawPattern != null) {
            item(key = "$tagPrefix-raw", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = onAddRawPattern, enabled = enabled,
                    modifier = Modifier.testTag("$tagPrefix-add")) { Text(rawPatternLabel) }
            }
        }
        if (available.isEmpty() && selected.isEmpty()) {
            item(key = "$tagPrefix-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(emptyText, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp).testTag("$tagPrefix-empty-text"))
            }
        } else {
            if (selected.isNotEmpty()) {
                item(key = "$tagPrefix-selected-header", span = { GridItemSpan(maxLineSpan) }) {
                    CategoryHeader(selectedLabel, MaterialTheme.colorScheme.primary)
                }
                items(items = selected, key = { "$tagPrefix-selected:${it.key}" }) { tag ->
                    BlacklistTagChip(tag, isSelected = true, enabled = enabled,
                        modifier = Modifier.testTag("$tagPrefix-selected:${tag.key}"),
                        onToggle = { onToggle(tag.key) }, onShowDetails = { onShowDetails(tag) })
                }
            }
            // The catalogue keeps the taxonomy's own order, so grouping by first appearance is grouping by category.
            available.groupBy { it.categoryId to it.categoryName }.forEach { (category, tags) ->
                item(key = "$tagPrefix-category:${category.first}", span = { GridItemSpan(maxLineSpan) }) {
                    CategoryHeader(category.second, MaterialTheme.colorScheme.primary)
                }
                items(items = tags, key = { "$tagPrefix:${it.key}" }) { tag ->
                    BlacklistTagChip(tag, isSelected = tag.key in selectedKeys, enabled = enabled,
                        modifier = Modifier.testTag("$tagPrefix:${tag.key}"),
                        onToggle = { onToggle(tag.key) }, onShowDetails = { onShowDetails(tag) })
                }
            }
        }
        footer?.let { footerContent ->
            item(key = "$tagPrefix-footer", span = { GridItemSpan(maxLineSpan) }) { footerContent() }
        }
    }
}

@Composable
private fun CategoryHeader(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

/** A tag as Android draws it: the label, its aliases underneath, and a clear mark once it is blacklisted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlacklistTagChip(
    tag: BlacklistTagUiModel,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onShowDetails: () -> Unit,
) {
    val showAliasesLabel = uiString("show_aliases")
    val removeLabel = uiString("remove")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { selected = isSelected }
            .combinedClickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onToggle,
                onLongClickLabel = showAliasesLabel,
                onLongClick = onShowDetails,
            ),
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tag.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (tag.aliases.isNotEmpty()) {
                    Text(tag.aliases.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (isSelected) {
                Icon(SettingsMaterialIcons.Close, contentDescription = removeLabel,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
