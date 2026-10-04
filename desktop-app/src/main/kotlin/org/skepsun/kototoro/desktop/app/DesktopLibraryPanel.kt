package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopLibraryPanel(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val history = state.screen == DesktopScreen.HISTORY
    val selection = if (history) state.historySelection else state.librarySelection
    var drawer by remember(history) { mutableStateOf(false) }
    val entries = remember(state.library, selection) { selection.select(state.library) }
    fun update(value: DesktopLibrarySelection) = controller.librarySelection(value)
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (drawer && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            drawer = false
            true
        } else false
    }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(selection.query, { update(selection.copy(query = it)) }, enabled = enabled,
                    singleLine = true, placeholder = { Text("搜索标题或作者") },
                    modifier = Modifier.weight(1f).testTag("library-query"))
                OutlinedButton({ drawer = !drawer }, modifier = Modifier.testTag("library-filters")) {
                    Text(if (selection.filterCount == 0) "筛选与排序" else "筛选 · ${selection.filterCount}")
                }
                TextButton({ controller.library(history) }, enabled = enabled) { Text("刷新") }
            }
            Text("${entries.size} / ${state.library.entries.size} 部作品", color = Muted, fontSize = 12.sp,
                modifier = Modifier.testTag("library-count"))
            Box(Modifier.weight(1f)) {
                if (entries.isNotEmpty()) ContentGrid(entries.map { it.content }, enabled, controller.session.covers,
                    controller::details)
                else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.library.entries.isEmpty()) "这里还没有作品" else "没有符合条件的作品",
                        fontWeight = FontWeight.SemiBold)
                    if (state.library.entries.isNotEmpty()) TextButton({ update(DesktopLibrarySelection()) },
                        modifier = Modifier.testTag("library-clear-empty")) { Text("清除搜索与筛选") }
                    else Text("从来源打开作品后，可阅读或加入收藏。", color = Muted)
                }
            }
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .08f)).clickable { drawer = false })
            Surface(Modifier.align(Alignment.CenterEnd).width(310.dp).fillMaxHeight()
                .testTag("library-filter-panel"), shape = RoundedCornerShape(20.dp), elevation = 12.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("筛选与排序", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        TextButton({ drawer = false }, modifier = Modifier.testTag("library-filter-close")) { Text("关闭") }
                    }
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("排序", fontWeight = FontWeight.SemiBold)
                            DesktopLibraryOrder.entries.forEach { order ->
                                FilterRadio(order.title, selection.order == order, enabled, "library-order:${order.name}") {
                                    update(selection.copy(order = order))
                                }
                            }
                            Divider()
                            Text("阅读进度", fontWeight = FontWeight.SemiBold)
                            DesktopLibraryReading.entries.forEach { reading ->
                                FilterRadio(reading.title, selection.reading == reading, enabled, "library-reading:${reading.name}") {
                                    update(selection.copy(reading = reading))
                                }
                            }
                            if (state.library.categories.isNotEmpty()) {
                                Divider()
                                Text("收藏分类", fontWeight = FontWeight.SemiBold)
                                FilterRadio("全部分类", selection.categoryId == null, enabled, "library-category:all") {
                                    update(selection.copy(categoryId = null))
                                }
                                state.library.categories.forEach { category ->
                                    FilterRadio(category.title, selection.categoryId == category.id, enabled,
                                        "library-category:${category.id}") { update(selection.copy(categoryId = category.id)) }
                                }
                            }
                            Divider()
                            Text("来源", fontWeight = FontWeight.SemiBold)
                            state.library.entries.map { it.content.source }.distinctBy { it.name }.forEach { source ->
                                val title = state.sources.firstOrNull { it.source.name == source.name }?.displayName ?: source.name
                                val selected = source.name in selection.sources
                                Row(Modifier.fillMaxWidth().testTag("library-source:${source.name}")
                                    .clickable(enabled = enabled) {
                                        update(selection.copy(sources = if (selected) selection.sources - source.name
                                            else selection.sources + source.name))
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selected, onCheckedChange = null, enabled = enabled)
                                    Text(title, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), modifier = Modifier.align(Alignment.CenterEnd)
                            .fillMaxHeight().testTag("library-filter-scrollbar"))
                    }
                    OutlinedButton({ update(DesktopLibrarySelection()) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("library-filter-reset")) { Text("重置搜索与筛选") }
                }
            }
        }
    }
}

@Composable
private fun FilterRadio(title: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, modifier = Modifier.padding(start = 12.dp))
    }
}
