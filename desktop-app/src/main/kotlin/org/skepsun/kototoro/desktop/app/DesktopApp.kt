package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.source.*
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal val Accent = Color(0xFF217A68)
internal val Canvas = Color(0xFFF4F6F9)
internal val Ink = Color(0xFF1B2635)
internal val Muted = Color(0xFF687385)

@Composable
private fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = lightColors(primary = Accent, secondary = Accent, background = Canvas,
        surface = Color.White, onSurface = Ink, onBackground = Ink), content = content)
}

@Composable
fun DesktopStartup(error: String?) = DesktopTheme {
    Box(Modifier.fillMaxSize().background(Canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Kototoro", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (error == null) {
                CircularProgressIndicator()
                Text("正在打开本地数据…", color = Muted)
            } else Text("启动失败：$error", color = MaterialTheme.colors.error, modifier = Modifier.widthIn(max = 700.dp))
        }
    }
}

@Composable
fun DesktopApp(controller: DesktopController, closing: Boolean = false, fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null) = DesktopTheme {
    val state by controller.state.collectAsState()
    val challenges = controller.session.browserChallenges
    val challenge = challenges?.pending?.collectAsState()?.value
    val browserScope = rememberCoroutineScope()
    val reading = state.screen == DesktopScreen.READER || state.screen == DesktopScreen.NOVEL ||
        state.screen == DesktopScreen.VIDEO
    Row(Modifier.fillMaxSize().background(Canvas)) {
        if (!reading) DesktopNavigationRail(controller, state, !state.busy && !closing)
        Column(Modifier.weight(1f).fillMaxHeight().padding(if (reading) 0.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (reading) 0.dp else 14.dp)) {
            if (!reading) Header(state)
            challenge?.let { pending ->
                var windowNotice by remember(pending.id) { mutableStateOf<String?>(null) }
                Surface(color = Color(0xFFE4F2EE), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("来源需要网页验证，请在浏览器窗口中完成操作。")
                        Text(pending.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { challenges.complete(pending.id, true) }, enabled = !closing,
                                modifier = Modifier.testTag("challenge-continue")) { Text("已完成，继续请求") }
                            OutlinedButton(onClick = { challenges.complete(pending.id, false) }, enabled = !closing,
                                modifier = Modifier.testTag("challenge-cancel")) { Text("取消验证") }
                            OutlinedButton(onClick = {
                                browserScope.launch {
                                    try {
                                        windowNotice = if (challenges.showWindow(pending.id)?.visible == true) {
                                            "验证窗口已显示"
                                        } else "验证已结束"
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { windowNotice = "无法显示验证窗口，请重试或取消验证" }
                                }
                            }, enabled = !closing, modifier = Modifier.testTag("challenge-show")) { Text("显示验证窗口") }
                        }
                        windowNotice?.let { Text(it) }
                    }
                }
            }
            if (state.busy || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Notice(it, true) }
            state.message?.let { Notice(it, false) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.screen) {
                    DesktopScreen.EXPLORE -> Browse(controller, state)
                    DesktopScreen.LIBRARY, DesktopScreen.HISTORY -> DesktopLibraryPanel(controller, state, !state.busy && !closing)
                    DesktopScreen.DETAILS -> Details(controller, state)
                    DesktopScreen.READER -> DesktopReader(controller, state, closing, fullscreen, onToggleFullscreen)
                    DesktopScreen.NOVEL -> DesktopNovelReader(controller, state, closing, fullscreen, onToggleFullscreen)
                    DesktopScreen.VIDEO -> DesktopVideoPlayer(controller, state, closing, fullscreen, onToggleFullscreen)
                    DesktopScreen.PREFERENCES -> Preferences(controller, state)
                    DesktopScreen.BROWSER -> BrowserPanel(controller.session)
                    DesktopScreen.DOWNLOADS -> DesktopDownloadsPanel(controller, closing)
                    DesktopScreen.BACKUPS -> DesktopBackupsPanel(controller, closing)
                    DesktopScreen.EXTENSIONS -> DesktopExtensionsPanel(controller, closing)
                    DesktopScreen.MORE -> DesktopMorePanel(controller, !state.busy && !closing)
                }
            }
        }
    }
}

@Composable
internal fun DesktopSourcePane(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var filter by remember { mutableStateOf("") }
    Column(Modifier.width(240.dp).fillMaxHeight().background(MaterialTheme.colors.surface,
        RoundedCornerShape(20.dp)).padding(16.dp).testTag("source-pane"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("我的来源", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Button(onClick = {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("扩展 JAR（Mihon / Kototoro / Kotatsu / UMA）", "jar")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) controller.importJar(chooser.selectedFile.toPath())
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("导入扩展 JAR") }
        Divider()
        Text("来源 · ${state.sources.size}", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(filter, { filter = it }, placeholder = { Text("查找来源") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("source-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sources = state.sources.filter { it.displayName.contains(filter, true) || it.source.locale.contains(filter, true) }
            items(sources, key = { it.source.name }) { source ->
                val selected = state.selectedSource?.source?.name == source.source.name
                Surface(color = if (selected) Accent.copy(alpha = .10f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().testTag("source:${source.source.name}")
                        .clickable(enabled = enabled) { controller.selectSource(source) }.padding(12.dp)) {
                        Text(source.displayName, maxLines = 2, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text("${DesktopSourceLabels.ecosystem(source.ecosystem)} · ${source.source.locale.ifBlank { "多语言" }}" +
                            " · ${DesktopSourceLabels.contentType(source.source.contentType)}", fontSize = 12.sp, color = Muted)
                    }
                }
            }
            if (sources.isEmpty()) item { Text("导入 JAR 后，来源会显示在这里", fontSize = 12.sp, color = Muted) }
        }
    }
}

@Composable
private fun Header(state: DesktopAppState) {
    val title = when (state.screen) {
        DesktopScreen.EXPLORE -> state.selectedSource?.displayName ?: "浏览来源"
        DesktopScreen.LIBRARY -> "收藏"
        DesktopScreen.HISTORY -> "阅读历史"
        DesktopScreen.DOWNLOADS -> "章节下载"
        DesktopScreen.BACKUPS -> "备份与恢复"
        DesktopScreen.EXTENSIONS -> "扩展与仓库"
        DesktopScreen.DETAILS -> "作品详情"
        DesktopScreen.READER, DesktopScreen.NOVEL, DesktopScreen.VIDEO -> state.chapter?.title ?: "阅读"
        DesktopScreen.PREFERENCES -> "来源设置"
        DesktopScreen.BROWSER -> "浏览器调试"
        DesktopScreen.MORE -> "更多"
    }
    Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
}

@Composable
private fun Notice(message: String, error: Boolean) {
    Surface(color = if (error) Color(0xFFFFEAEA) else Color(0xFFE4F2EE), shape = RoundedCornerShape(8.dp)) {
        Text(message, color = if (error) MaterialTheme.colors.error else Accent,
            modifier = Modifier.fillMaxWidth().padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Browse(controller: DesktopController, state: DesktopAppState) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        DesktopSourcePane(controller, state, !state.busy)
        Box(Modifier.weight(1f).fillMaxHeight()) { SourceResults(controller, state) }
    }
}

@Composable
private fun SourceResults(controller: DesktopController, state: DesktopAppState) {
    if (state.selectedSource == null) {
        EmptyPanel("你的 Windows 阅读空间", "在“扩展与仓库”中安装来源，或导入本地 JAR，然后从左侧选择来源开始浏览。")
        return
    }
    var query by remember(state.selectedSource.source.name, state.query) { mutableStateOf(state.query) }
    if (state.filterDialogOpen) DesktopFilters(controller, state, query)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索作品") }, singleLine = true,
                enabled = !state.busy, modifier = Modifier.weight(1f).testTag("source-query"))
            Button({ controller.browse(0, query) }, enabled = !state.busy) { Text("搜索") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val orders = state.descriptor?.sortOrders.orEmpty()
            if (state.descriptor == null || "POPULARITY" in orders) {
                OutlinedButton({ query = ""; controller.browseUnfiltered() }, enabled = !state.busy) { Text("热门") }
            }
            if ("UPDATED" in orders) {
                OutlinedButton({ query = ""; controller.browseUnfiltered(latest = true) }, enabled = !state.busy) { Text("最新") }
            }
            // Sources with more than the two Mihon orders (all parser plugins) get the full list.
            if (orders.any { it != "POPULARITY" && it != "UPDATED" }) {
                var expanded by remember(state.selectedSource.source.name) { mutableStateOf(false) }
                Box {
                    OutlinedButton({ expanded = true }, enabled = !state.busy, modifier = Modifier.testTag("source-sort")) {
                        Text("排序 · ${DesktopSourceLabels.sortOrder(state.browseOrder ?: state.descriptor?.defaultSortOrder.orEmpty())}")
                    }
                    DropdownMenu(expanded && !state.busy, { expanded = false }) {
                        orders.forEach { order ->
                            DropdownMenuItem({ expanded = false; controller.browseOrdered(order) },
                                modifier = Modifier.testTag("source-sort:$order")) { Text(DesktopSourceLabels.sortOrder(order)) }
                        }
                    }
                }
            }
            if (state.descriptor?.isDynamicFilteringSupported == true) {
                OutlinedButton({ controller.filters() }, enabled = !state.busy,
                    modifier = Modifier.testTag("source-filters")) {
                    Text(if (state.appliedFilters.isEmpty()) "筛选" else "筛选 · 已应用")
                }
            }
            if (state.descriptor?.isPreferencesSupported == true) {
                OutlinedButton({ controller.preferences() }, enabled = !state.busy) { Text("源设置") }
            }
        }
        Box(Modifier.weight(1f)) { ContentGrid(state.items, !state.busy, controller.session.covers, controller::details) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.browse((state.offset - 1).coerceAtLeast(0)) },
                enabled = !state.busy && state.offset > 0) { Text("上一批") }
            Text("第 ${state.offset + 1} 批", color = Muted)
            OutlinedButton({ controller.browse(state.offset + 1) },
                enabled = !state.busy && state.items.isNotEmpty()) { Text("下一批") }
        }
    }
}

@Composable
internal fun ContentGrid(contents: List<SourceContent>, enabled: Boolean, covers: DesktopCovers,
    onOpen: (SourceContent) -> Unit) {
    if (contents.isEmpty()) { EmptyPanel("这里还没有作品", "选择来源浏览，或在作品详情中加入收藏。"); return }
    LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        items(contents, key = { it.id }) { content ->
            Card(Modifier.fillMaxWidth().testTag("content:${content.id}").clickable(enabled = enabled) { onOpen(content) },
                shape = RoundedCornerShape(12.dp), backgroundColor = Color.Transparent, elevation = 0.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopCover(content, covers, Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                        .padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp))
                        .background(Accent.copy(alpha = .06f), RoundedCornerShape(8.dp)))
                    Text(content.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(content.authors.joinToString(" · ").ifBlank { content.source.locale },
                        fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Details(controller: DesktopController, state: DesktopAppState) {
    DesktopTabletDetails(controller, state)
}

@Composable
private fun Preferences(controller: DesktopController, state: DesktopAppState) {
    val screen = state.preferences ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton({ controller.browse() }, enabled = !state.busy) { Text("返回浏览") }
        screen.nodes.forEach { node -> PreferenceNode(controller, node, !state.busy) }
    }
}

@Composable
private fun PreferenceNode(controller: DesktopController, node: SourcePreferenceNode, active: Boolean) {
    if (!node.visible) return
    val enabled = active && node.enabled
    Surface(shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(node.title.ifBlank { node.key.orEmpty() }, fontWeight = FontWeight.SemiBold)
            if (node.summary.isNotBlank()) Text(node.summary, color = Muted, fontSize = 12.sp)
            when (node.kind) {
                SourcePreferenceKind.TEXT -> {
                    var text by remember(node.id, node.value) { mutableStateOf((node.value as? SourcePreferenceValue.Text)?.value.orEmpty()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(text, { text = it }, enabled = enabled, modifier = Modifier.weight(1f))
                        Button({ controller.updatePreference(node, SourcePreferenceValue.Text(text)) }, enabled = enabled) { Text("保存") }
                    }
                }
                SourcePreferenceKind.TOGGLE -> Switch((node.value as? SourcePreferenceValue.Toggle)?.value ?: false,
                    { controller.updatePreference(node, SourcePreferenceValue.Toggle(it)) }, enabled = enabled)
                SourcePreferenceKind.CHOICE -> node.choices.forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton((node.value as? SourcePreferenceValue.Text)?.value == choice.value,
                            { controller.updatePreference(node, SourcePreferenceValue.Text(choice.value)) }, enabled = enabled,
                            modifier = Modifier.testTag("preference:${node.id}:${choice.value}"))
                        Text(choice.title)
                    }
                }
                SourcePreferenceKind.MULTI_CHOICE -> node.choices.forEach { choice ->
                    val values = (node.value as? SourcePreferenceValue.TextSet)?.values.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(choice.value in values, { checked ->
                            controller.updatePreference(node, SourcePreferenceValue.TextSet(
                                if (checked) values + choice.value else values - choice.value))
                        }, enabled = enabled)
                        Text(choice.title)
                    }
                }
                SourcePreferenceKind.GROUP -> node.children.forEach { PreferenceNode(controller, it, enabled) }
                SourcePreferenceKind.UNSUPPORTED -> Text("此控件尚未支持", fontSize = 12.sp, color = Muted)
                SourcePreferenceKind.INFO -> Unit
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(detail, color = Muted)
        }
    }
}
