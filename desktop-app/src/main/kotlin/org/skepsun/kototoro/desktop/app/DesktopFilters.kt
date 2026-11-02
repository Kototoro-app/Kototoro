package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.core.ui.chips.ChoiceChip
import org.skepsun.kototoro.core.ui.chips.ChoiceChipFlow

/** Edits are local to the dialog; only Apply publishes a typed request to the source. */
@Composable
internal fun DesktopFilters(controller: DesktopController, state: DesktopAppState, query: String) {
    val definition = state.dynamicFilters ?: return
    val defaults = remember(definition) {
        MihonFilterRules.flatten(definition.nodes).filter { it.kind in setOf(SourceFilterKind.CHECKBOX,
            SourceFilterKind.TRISTATE, SourceFilterKind.SELECT, SourceFilterKind.SORT, SourceFilterKind.TEXT) }
            .associate { it.id to it.state }
    }
    val draft = remember(definition, state.appliedFilters) {
        mutableStateMapOf<String, SourceFilterValue?>().apply {
            putAll(defaults)
            state.appliedFilters.filter { it.id in defaults }.forEach { put(it.id, it.value) }
        }
    }
    val rows = remember(definition) {
        fun walk(nodes: List<SourceFilterNode>, depth: Int): List<Pair<SourceFilterNode, Int>> =
            nodes.flatMap { listOf(it to depth) + walk(it.children, depth + 1) }
        walk(definition.nodes, 0)
    }
    // Keep the draft in composition while exposing the main window's challenge/cancellation controls.
    if (state.busy) return
    AlertDialog(onDismissRequest = { if (!state.busy) controller.dismissFilters() },
        modifier = Modifier.width(580.dp).testTag("filter-dialog"), title = { Text(desktopText("filters.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.first.id }) { (node, depth) ->
                        Box(Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
                            FilterControl(node, draft[node.id], !state.busy) { draft[node.id] = it }
                        }
                    }
                }
                if (rows.isEmpty()) Text(desktopText("filters.empty"))
            }
        }, confirmButton = {
            Button({ controller.applyFilters(query, draft.map { SourceFilterChange(it.key, it.value) }) },
                enabled = !state.busy, modifier = Modifier.testTag("filter-apply")) { Text(desktopText("common.apply")) }
        }, dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ draft.clear(); draft.putAll(defaults) }, enabled = !state.busy,
                    modifier = Modifier.testTag("filter-reset")) { Text(desktopText("filters.restoreDefaults")) }
                TextButton({ controller.dismissFilters() }, enabled = !state.busy,
                    modifier = Modifier.testTag("filter-cancel")) { Text(desktopText("common.cancel")) }
            }
        })
}

@Composable
internal fun FilterControl(node: SourceFilterNode, value: SourceFilterValue?, enabled: Boolean,
    onChange: (SourceFilterValue?) -> Unit) {
    val tag = "filter:${node.id}"
    when (node.kind) {
        SourceFilterKind.HEADER, SourceFilterKind.GROUP -> Text(node.name, fontWeight = FontWeight.SemiBold)
        SourceFilterKind.SEPARATOR -> HorizontalDivider()
        SourceFilterKind.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox((value as? SourceFilterValue.Toggle)?.value ?: false,
                { onChange(SourceFilterValue.Toggle(it)) }, enabled = enabled, modifier = Modifier.testTag(tag))
            Text(node.name)
        }
        SourceFilterKind.TRISTATE -> {
            val tri = (value as? SourceFilterValue.TriState)?.value ?: SourceTriState.IGNORE
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, modifier = Modifier.weight(1f))
                OutlinedButton({ onChange(SourceFilterValue.TriState(
                    SourceTriState.entries[(tri.ordinal + 1) % SourceTriState.entries.size])) },
                    enabled = enabled, modifier = Modifier.testTag(tag)) {
                    Text(when (tri) {
                        SourceTriState.IGNORE -> desktopText("filters.triStateIgnore")
                        SourceTriState.INCLUDE -> desktopText("filters.triStateInclude")
                        SourceTriState.EXCLUDE -> desktopText("filters.triStateExclude")
                    })
                }
            }
        }
        SourceFilterKind.TEXT -> OutlinedTextField((value as? SourceFilterValue.Text)?.value.orEmpty(),
            { onChange(SourceFilterValue.Text(it)) }, label = { Text(node.name) }, enabled = enabled,
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
        SourceFilterKind.SELECT, SourceFilterKind.SORT -> {
            val sort = value as? SourceFilterValue.Sort
            val index = (value as? SourceFilterValue.Choice)?.index ?: sort?.index
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.name)
                fun pick(position: Int) {
                    onChange(if (node.kind == SourceFilterKind.SELECT) SourceFilterValue.Choice(position)
                        else SourceFilterValue.Sort(position, sort?.ascending ?: false))
                }
                // Android's filter sheet draws its own short option sets as chips: a desktop window fits them all, so
                // the same row costs one click where the menu costs two. A sort keeps its direction and reset beside
                // the chips on that same line, which is also how the menu row lays them out.
                if (node.values.size in 1..CHIP_OPTION_LIMIT) {
                    ChoiceChipFlow {
                        node.values.forEachIndexed { position, label ->
                            ChoiceChip(selected = index == position, onClick = { pick(position) }, enabled = enabled,
                                modifier = Modifier.testTag("$tag:option:$position")) { Text(label) }
                        }
                        if (node.kind == SourceFilterKind.SORT) SortActions(tag, sort, enabled, onChange)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        var expanded by remember(node.id) { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton({ expanded = true }, enabled = enabled && node.values.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().testTag(tag)) {
                                Text(index?.let { node.values.getOrNull(it) } ?: desktopText("filters.unselected"))
                            }
                            DropdownMenu(expanded && enabled, { expanded = false }) {
                                node.values.forEachIndexed { position, label ->
                                    DropdownMenuItem(onClick = { expanded = false; pick(position) },
                                        modifier = Modifier.testTag("$tag:option:$position"),
                                        text = { Text(label) })
                                }
                            }
                        }
                        if (node.kind == SourceFilterKind.SORT) SortActions(tag, sort, enabled, onChange)
                    }
                }
            }
        }
        SourceFilterKind.UNSUPPORTED -> Text(desktopText("filters.unsupported", node.name), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
    }
}

/** A sort's direction and its "no sort" reset, shared by the chip row and the menu row. */
@Composable
private fun SortActions(tag: String, sort: SourceFilterValue.Sort?, enabled: Boolean,
    onChange: (SourceFilterValue?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ sort?.let { onChange(it.copy(ascending = !it.ascending)) } },
            enabled = enabled && sort != null, modifier = Modifier.testTag("$tag:direction")) {
            Text(if (sort?.ascending == true) desktopText("filters.ascending") else desktopText("filters.descending"))
        }
        TextButton({ onChange(null) }, enabled = enabled && sort != null,
            modifier = Modifier.testTag("$tag:clear")) { Text(desktopText("common.clear")) }
    }
}

/**
 * Android's own sheet chips a short option set (`CompactFilterChipFlow`); a list longer than this is a real menu,
 * where every option would otherwise become a chip the reader has to scan.
 */
private const val CHIP_OPTION_LIMIT = 5
