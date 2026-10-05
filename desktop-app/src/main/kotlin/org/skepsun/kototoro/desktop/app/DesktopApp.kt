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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
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
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgePill
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeText
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeTone
import org.skepsun.kototoro.list.ui.compose.ContentCardBottomProgressBar
import org.skepsun.kototoro.list.ui.compose.TabletPosterCover
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.list.ui.compose.rememberCoverRimBorderBrush
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

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
    onToggleFullscreen: (() -> Unit)? = null) = DesktopTheme(controller.state.collectAsState().value.appearance,
        controller.state.collectAsState().value.interfaceStyle) {
    val state by controller.state.collectAsState()
    val challenges = controller.session.browserChallenges
    val challenge = challenges?.pending?.collectAsState()?.value
    val browserScope = rememberCoroutineScope()
    val reading = state.screen == DesktopScreen.READER || state.screen == DesktopScreen.NOVEL ||
        state.screen == DesktopScreen.VIDEO
    val listState = if (state.screen == DesktopScreen.DETAILS && state.detailsOrigin != null) {
        state.copy(screen = requireNotNull(state.detailsOrigin))
    } else state
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val backdrop = rememberLayerBackdrop()
        // Record the canvas before controls draw, so a glass surface never samples itself.
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Canvas).background(desktopCanvasBrush()))
        CompositionLocalProvider(LocalDesktopWindowWidth provides maxWidth, LocalDesktopBackdrop provides backdrop) {
            Row(Modifier.fillMaxSize()) {
                if (!reading) DesktopNavigationRail(controller, listState, !closing)
                Column(Modifier.weight(1f).fillMaxHeight().padding(if (reading) 0.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (reading) 0.dp else 14.dp)) {
                    if (!reading && listState.screen !in setOf(DesktopScreen.LIBRARY, DesktopScreen.HISTORY,
                            DesktopScreen.EXPLORE, DesktopScreen.HOME, DesktopScreen.FEED)) Header(listState)
                    challenge?.let { pending ->
                        var windowNotice by remember(pending.id) { mutableStateOf<String?>(null) }
                        Surface(color = MaterialTheme.colors.surface, shape = RoundedCornerShape(20.dp)) {
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
                        when (listState.screen) {
                            DesktopScreen.HOME -> DesktopHomePanel(controller, listState, !closing)
                            DesktopScreen.FEED -> DesktopFeedPanel(controller, listState, !closing)
                            DesktopScreen.EXPLORE -> Browse(controller, listState, !closing)
                            DesktopScreen.LIBRARY, DesktopScreen.HISTORY -> DesktopLibraryPanel(controller, listState, !closing)
                            DesktopScreen.DETAILS -> Details(controller, state)
                            DesktopScreen.READER -> DesktopReader(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.NOVEL -> DesktopNovelReader(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.VIDEO -> DesktopVideoPlayer(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.PREFERENCES -> Preferences(controller, state)
                            DesktopScreen.BROWSER -> BrowserPanel(controller.session)
                            DesktopScreen.DOWNLOADS -> DesktopDownloadsPanel(controller, closing)
                            DesktopScreen.BACKUPS -> DesktopBackupsPanel(controller, closing)
                            DesktopScreen.EXTENSIONS -> DesktopExtensionsPanel(controller, closing)
                            DesktopScreen.MORE -> DesktopMorePanel(controller, !closing)
                        }
                        if (state.screen == DesktopScreen.DETAILS && state.detailsOrigin != null) {
                            DesktopDetailsOverlay(controller, state, !state.busy && !closing)
                        }
                    }
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
                fileFilter = FileNameExtensionFilter("扩展（Mihon / Kototoro / Kotatsu / UMA JAR、Aniyomi APK、Cloudstream .cs3）", "jar", "apk", "cs3")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) controller.importJar(chooser.selectedFile.toPath())
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("导入扩展 JAR") }
        Divider()
        Text("来源 · ${state.sources.size}", fontWeight = FontWeight.SemiBold)
        DesktopSearchField(filter, { filter = it }, "查找来源", modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("source-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sources = state.sources.filter {
                (it.displayName.contains(filter, true) || it.source.locale.contains(filter, true)) &&
                    state.exploreFilter.accepts(it)
            }
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
        DesktopScreen.HOME -> "主页"
        DesktopScreen.FEED -> "订阅"
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
    Text(title, style = MaterialTheme.typography.h5, color = Ink,
        modifier = Modifier.heightIn(min = 40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Notice(message: String, error: Boolean) {
    Surface(color = MaterialTheme.colors.surface, shape = RoundedCornerShape(16.dp)) {
        // Errors are selectable so a source failure can be copied into a report.
        SelectionContainer {
            Text(message, color = if (error) MaterialTheme.colors.error else Accent,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(if (error) "notice-error" else "notice-message"),
                maxLines = if (error) 12 else 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Browse(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    // Browsing stays usable while a source loads: a newer request replaces the pending one.
    if (state.selectedSource == null) {
        DesktopSourceGrid(controller, state, enabled)
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        DesktopSourcePane(controller, state, enabled)
        Box(Modifier.weight(1f).fillMaxHeight()) { SourceResults(controller, state, enabled) }
    }
}

@Composable
private fun SourceResults(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    if (state.selectedSource == null) {
        EmptyPanel("你的 Windows 阅读空间", "在“扩展与仓库”中安装来源，或导入本地 JAR，然后从左侧选择来源开始浏览。")
        return
    }
    var query by remember(state.selectedSource.source.name, state.query) { mutableStateOf(state.query) }
    if (state.filterDialogOpen) DesktopFilters(controller, state, query)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ controller.exitSource() }, enabled = enabled, modifier = Modifier.testTag("source-back")) {
                // Android's forward arrow, mirrored.
                Icon(painterResource("icons/ic_arrow_forward.svg"), contentDescription = "返回来源列表", tint = Ink,
                    modifier = Modifier.size(22.dp).graphicsLayer(scaleX = -1f))
            }
            Text(state.selectedSource.displayName, style = MaterialTheme.typography.h5, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DesktopSearchField(query, { query = it }, "搜索作品", enabled = enabled,
                modifier = Modifier.weight(1f), tag = "source-query")
            Button({ controller.browse(0, query) }, enabled = enabled) { Text("搜索") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val orders = state.descriptor?.sortOrders.orEmpty()
            if (state.descriptor == null || "POPULARITY" in orders) {
                OutlinedButton({ query = ""; controller.browseUnfiltered() }, enabled = enabled) { Text("热门") }
            }
            if ("UPDATED" in orders) {
                OutlinedButton({ query = ""; controller.browseUnfiltered(latest = true) }, enabled = enabled) { Text("最新") }
            }
            // Sources with more than the two Mihon orders (all parser plugins) get the full list.
            if (orders.any { it != "POPULARITY" && it != "UPDATED" }) {
                var expanded by remember(state.selectedSource.source.name) { mutableStateOf(false) }
                Box {
                    OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("source-sort")) {
                        Text("排序 · ${DesktopSourceLabels.sortOrder(state.browseOrder ?: state.descriptor?.defaultSortOrder.orEmpty())}")
                    }
                    DropdownMenu(expanded && enabled, { expanded = false }) {
                        orders.forEach { order ->
                            DropdownMenuItem({ expanded = false; controller.browseOrdered(order) },
                                modifier = Modifier.testTag("source-sort:$order")) { Text(DesktopSourceLabels.sortOrder(order)) }
                        }
                    }
                }
            }
            if (state.descriptor?.isDynamicFilteringSupported == true) {
                OutlinedButton({ controller.filters() }, enabled = enabled,
                    modifier = Modifier.testTag("source-filters")) {
                    Text(if (state.appliedFilters.isEmpty()) "筛选" else "筛选 · 已应用")
                }
            }
            if (state.descriptor?.isPreferencesSupported == true) {
                OutlinedButton({ controller.preferences() }, enabled = enabled) { Text("源设置") }
            }
        }
        Box(Modifier.weight(1f)) { ContentGrid(state.items, enabled, controller.session.covers, controller::details) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.browse((state.offset - 1).coerceAtLeast(0)) },
                enabled = enabled && state.offset > 0) { Text("上一批") }
            Text("第 ${state.offset + 1} 批", color = Muted)
            OutlinedButton({ controller.browse(state.offset + 1) },
                enabled = enabled && state.items.isNotEmpty()) { Text("下一批") }
        }
    }
}

@Composable
internal fun ContentGrid(contents: List<SourceContent>, enabled: Boolean, covers: DesktopCovers,
    onOpen: (SourceContent) -> Unit, progressOf: (SourceContent) -> Float? = { null }) {
    if (contents.isEmpty()) { EmptyPanel("这里还没有作品", "选择来源浏览，或在作品详情中加入收藏。"); return }
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rimBorderBrush = rememberCoverRimBorderBrush(isIosStyle, isDark = !MaterialTheme.colors.isLight)
    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize().testTag("content-grid")) {
        items(contents, key = { it.id }) { content ->
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("content:${content.id}")
                .clickable(enabled = enabled) { onOpen(content) }) {
                val style = CompactPosterCardStyle(maxWidth, maxWidth * (136f / 96f), 12.dp)
                val metrics = remember(maxWidth) { contentCardBadgeMetricsFor(maxWidth) }
                val percent = progressOf(content)?.takeIf { ReadingProgress.isValid(it) }
                // Android's compact grid lifts the bottom badges clear of the title on the scrim.
                val bottomLift = (style.posterHeight.value * 0.28f).dp.coerceIn(32.dp, 44.dp)
                TabletPosterCover(content.title, style, rimBorderBrush = rimBorderBrush,
                    modifier = Modifier.fillMaxWidth().aspectRatio(96f / 136f),
                    cover = { DesktopCover(content, covers, Modifier.matchParentSize()) },
                    overlays = {
                        val locale = content.source.locale.substringBefore('-').substringBefore('_').uppercase()
                        if (locale.isNotBlank()) ContentCardBadgePill(ContentCardBadgeTone.NEUTRAL, isIosStyle, metrics,
                            Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = bottomLift + 5.dp)
                                .testTag("content-language:${content.id}")) { colors ->
                            ContentCardBadgeText(locale, colors.content, metrics)
                        }
                    },
                    progress = {
                        if (percent != null) ContentCardBottomProgressBar(percent, ReadingProgress.isCompleted(percent),
                            Modifier.align(Alignment.BottomCenter).testTag("content-progress:${content.id}"))
                    },
                )
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
        // Kototoro's own rows (User-Agent) come first, as in Android's source settings.
        screen.nodes.sortedBy { if (it.id.startsWith("host:")) 0 else 1 }
            .forEach { node -> PreferenceNode(controller, node, !state.busy) }
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
