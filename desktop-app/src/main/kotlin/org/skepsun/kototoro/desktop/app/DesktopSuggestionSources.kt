package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing

/**
 * Android's `SuggestionSourcesDialog` (`settings/compose/SuggestionsSettingsScreen.kt`): the "preferred sources" and
 * "excluded sources" preferences do not edit a flat list of every installed source, they open a picker with a search
 * field, one filter row per facet (content type, source type) and a pending selection that is committed on OK. Windows
 * had squashed this into a plain check-box dialog that wrote on every click, so the pure parts are kept here as plain
 * functions and the picker below only wires them to Compose.
 */

/** One row of the picker: the source identity, its display name and the two facets Android filters on. */
internal data class SuggestionSourceOption(
    val id: String,
    val title: String,
    val contentType: String,
    val ecosystem: SourceEcosystem,
)

/** Android reads its options from the installed source list, in that same order; so does this. */
internal fun suggestionSourceOptions(sources: List<SourceListing>): List<SuggestionSourceOption> = sources.map {
    SuggestionSourceOption(it.source.name, it.displayName, it.source.contentType, it.ecosystem)
}

/**
 * Android filters by content type, source type and a case-insensitive title/id query; an empty facet set means "all",
 * which is exactly what the "all content" / "all sources" chips reset to.
 */
internal fun filterSuggestionSourceOptions(
    options: List<SuggestionSourceOption>,
    query: String,
    contentTypes: Set<String>,
    ecosystems: Set<SourceEcosystem>,
): List<SuggestionSourceOption> {
    val normalized = query.trim()
    return options.filter { option ->
        (contentTypes.isEmpty() || option.contentType in contentTypes) &&
            (ecosystems.isEmpty() || option.ecosystem in ecosystems) &&
            (normalized.isEmpty() ||
                option.title.contains(normalized, ignoreCase = true) ||
                option.id.contains(normalized, ignoreCase = true))
    }
}

/**
 * Android's preference summary: the first three selected names, then how many are left over; nothing selected falls
 * back to the explanation string the row shows while the list is empty.
 */
internal fun suggestionSourceSummary(
    selectedIds: Set<String>,
    options: List<SuggestionSourceOption>,
    empty: String,
): String {
    val titles = options.filter { it.id in selectedIds }.map { it.title }
    return when {
        titles.isEmpty() -> empty
        titles.size <= 3 -> titles.joinToString(", ")
        else -> titles.take(3).joinToString(", ") + " +${titles.size - 3}"
    }
}

/**
 * Android orders the content-type chips by its `ContentType` ordinal, which Windows only sees as a name; this mirrors
 * that order and leaves anything the host does not know at the end, alphabetically.
 */
private val CONTENT_TYPE_ORDER = listOf(
    "MANGA", "MANHWA", "MANHUA", "HENTAI", "COMICS", "NOVEL", "ONE_SHOT", "DOUJINSHI", "IMAGE_SET", "ARTIST_CG",
    "GAME_CG", "VIDEO", "OTHER",
)

internal fun availableContentTypes(options: List<SuggestionSourceOption>): List<String> = options
    .map { it.contentType }
    .distinct()
    .sortedWith(compareBy({ CONTENT_TYPE_ORDER.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }, { it }))

internal fun availableEcosystems(options: List<SuggestionSourceOption>): List<SourceEcosystem> = options
    .map { it.ecosystem }
    .distinct()

/**
 * The picker itself. The selection is pending until OK, so "clear" can drop it and cancel can restore it, which is the
 * one behaviour the old write-on-click dialog could not express.
 */
@Composable
internal fun DesktopSuggestionSourcesDialog(
    title: String,
    options: List<SuggestionSourceOption>,
    selectedIds: Set<String>,
    bodyTag: String,
    optionTagPrefix: String,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pending by remember(selectedIds) { mutableStateOf(selectedIds) }
    var contentTypes by remember { mutableStateOf(emptySet<String>()) }
    var ecosystems by remember { mutableStateOf(emptySet<SourceEcosystem>()) }
    val visible = remember(options, query, contentTypes, ecosystems) {
        filterSuggestionSourceOptions(options, query, contentTypes, ecosystems)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(520.dp).testTag(bodyTag)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    placeholder = { Text(AndroidStrings["search"]) },
                    modifier = Modifier.fillMaxWidth().testTag("$bodyTag-query"),
                )
                SuggestionFilterRow(
                    title = AndroidStrings["content_types"],
                    allLabel = AndroidStrings["all_content"],
                    allTag = "$bodyTag-content-all",
                    optionTag = "$bodyTag-content",
                    options = availableContentTypes(options),
                    selected = contentTypes,
                    label = { DesktopSourceLabels.contentType(it) },
                    onAll = { contentTypes = emptySet() },
                    onToggle = { contentTypes = contentTypes.toggle(it) },
                )
                SuggestionFilterRow(
                    title = AndroidStrings["source_types"],
                    allLabel = AndroidStrings["all_sources"],
                    allTag = "$bodyTag-ecosystem-all",
                    optionTag = "$bodyTag-ecosystem",
                    options = availableEcosystems(options),
                    selected = ecosystems,
                    label = { DesktopSourceLabels.ecosystem(it) },
                    onAll = { ecosystems = emptySet() },
                    onToggle = { ecosystems = ecosystems.toggle(it) },
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(visible, key = { it.id }) { option ->
                        val checked = option.id in pending
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { pending = pending.toggle(option.id, checked) }
                                .padding(vertical = 4.dp).testTag("$optionTagPrefix:${option.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(option.title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(pending) }, modifier = Modifier.testTag("$bodyTag-confirm")) {
                Text(desktopText("common.ok"))
            }
        },
        dismissButton = {
            Row {
                TextButton({ pending = emptySet() }, modifier = Modifier.testTag("$bodyTag-clear")) {
                    Text(desktopText("common.clear"))
                }
                TextButton(onDismiss, modifier = Modifier.testTag("$bodyTag-cancel")) {
                    Text(desktopText("common.cancel"))
                }
            }
        },
    )
}

/** One titled chip row; the leading chip is "all", selected while the facet is unfiltered, like Android's. */
@Composable
private fun <T> SuggestionFilterRow(
    title: String,
    allLabel: String,
    allTag: String,
    optionTag: String,
    options: List<T>,
    selected: Set<T>,
    label: @Composable (T) -> String,
    onAll: () -> Unit,
    onToggle: (T) -> Unit,
) {
    Text(title, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item("all") {
            FilterChip(selected = selected.isEmpty(), onClick = onAll, label = { Text(allLabel) },
                modifier = Modifier.testTag(allTag))
        }
        items(options) { option ->
            FilterChip(selected = option in selected, onClick = { onToggle(option) },
                label = { Text(label(option)) }, modifier = Modifier.testTag("$optionTag:$option"))
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private fun Set<String>.toggle(value: String, checked: Boolean): Set<String> =
    if (checked) this - value else this + value
