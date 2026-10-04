package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.*

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
        modifier = Modifier.width(580.dp).testTag("filter-dialog"), title = { Text("来源筛选") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.error != null) Text(state.error, color = MaterialTheme.colors.error)
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.first.id }) { (node, depth) ->
                        Box(Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
                            FilterControl(node, draft[node.id], !state.busy) { draft[node.id] = it }
                        }
                    }
                }
                if (rows.isEmpty()) Text("此来源没有筛选项")
            }
        }, buttons = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ draft.clear(); draft.putAll(defaults) }, enabled = !state.busy,
                    modifier = Modifier.testTag("filter-reset")) { Text("恢复默认") }
                Spacer(Modifier.weight(1f))
                TextButton({ controller.dismissFilters() }, enabled = !state.busy,
                    modifier = Modifier.testTag("filter-cancel")) { Text("取消") }
                Button({ controller.applyFilters(query, draft.map { SourceFilterChange(it.key, it.value) }) },
                    enabled = !state.busy, modifier = Modifier.testTag("filter-apply")) { Text("应用") }
            }
        })
}

@Composable
private fun FilterControl(node: SourceFilterNode, value: SourceFilterValue?, enabled: Boolean,
    onChange: (SourceFilterValue?) -> Unit) {
    val tag = "filter:${node.id}"
    when (node.kind) {
        SourceFilterKind.HEADER, SourceFilterKind.GROUP -> Text(node.name, fontWeight = FontWeight.SemiBold)
        SourceFilterKind.SEPARATOR -> Divider()
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
                        SourceTriState.IGNORE -> "不限"
                        SourceTriState.INCLUDE -> "包含"
                        SourceTriState.EXCLUDE -> "排除"
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
            Column {
                Text(node.name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var expanded by remember(node.id) { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton({ expanded = true }, enabled = enabled && node.values.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().testTag(tag)) {
                            Text(index?.let { node.values.getOrNull(it) } ?: "未选择")
                        }
                        DropdownMenu(expanded && enabled, { expanded = false }) {
                            node.values.forEachIndexed { position, label ->
                                DropdownMenuItem({ expanded = false
                                    onChange(if (node.kind == SourceFilterKind.SELECT) SourceFilterValue.Choice(position)
                                        else SourceFilterValue.Sort(position, sort?.ascending ?: false)) },
                                    modifier = Modifier.testTag("$tag:option:$position")) { Text(label) }
                            }
                        }
                    }
                    if (node.kind == SourceFilterKind.SORT) {
                        OutlinedButton({ sort?.let { onChange(it.copy(ascending = !it.ascending)) } },
                            enabled = enabled && sort != null, modifier = Modifier.testTag("$tag:direction")) {
                            Text(if (sort?.ascending == true) "升序" else "降序")
                        }
                        TextButton({ onChange(null) }, enabled = enabled && sort != null,
                            modifier = Modifier.testTag("$tag:clear")) { Text("清除") }
                    }
                }
            }
        }
        SourceFilterKind.UNSUPPORTED -> Text("${node.name}：暂不支持此控件", color = MaterialTheme.colors.onSurface.copy(alpha = .6f))
    }
}
