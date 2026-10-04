package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mirrors Android's landscape rail; source selection belongs to Browse rather than global navigation. */
@Composable
internal fun DesktopNavigationRail(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    Column(Modifier.width(80.dp).fillMaxHeight().background(MaterialTheme.colors.surface)
        .padding(vertical = 16.dp).testTag("tablet-navigation"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("K", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
        RailDestination("收藏", "ic_bookmark", state.screen == DesktopScreen.LIBRARY, enabled) {
            controller.library()
        }
        RailDestination("浏览", "ic_explore_normal", state.screen in setOf(DesktopScreen.EXPLORE,
            DesktopScreen.DETAILS, DesktopScreen.PREFERENCES), enabled) { controller.explore() }
        RailDestination("历史", "ic_history", state.screen == DesktopScreen.HISTORY, enabled) {
            controller.library(true)
        }
        Spacer(Modifier.weight(1f))
        RailDestination("更多", "ic_more_vert", state.screen in setOf(DesktopScreen.MORE, DesktopScreen.DOWNLOADS,
            DesktopScreen.EXTENSIONS, DesktopScreen.BACKUPS, DesktopScreen.BROWSER), enabled) {
            controller.more()
        }
    }
}

@Composable
internal fun DesktopMorePanel(controller: DesktopController, enabled: Boolean) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MoreDestination("扩展与仓库", "ic_extension", "管理来源、添加仓库与导入本地扩展", enabled) {
            controller.showExtensions()
        }
        MoreDestination("下载", "ic_download", "查看离线章节与下载任务", enabled) { controller.showDownloads() }
        MoreDestination("备份与恢复", "ic_backup_restore", "导出书库，或预览并合并备份", enabled) { controller.showBackups() }
        Divider(Modifier.padding(vertical = 12.dp))
        Text("高级工具", fontWeight = FontWeight.SemiBold, color = Muted)
        MoreDestination("浏览器调试", "ic_web", "打开网页，检查来源的网页验证", enabled) { controller.browser() }
    }
}

@Composable
private fun MoreDestination(title: String, icon: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().testTag("nav:$title").clickable(enabled = enabled, onClick = onClick)
            .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource("icons/$icon.svg"), null, tint = Accent, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = Muted, fontSize = 13.sp)
            }
            Text("›", fontSize = 24.sp, color = Muted)
        }
    }
}

@Composable
private fun RailDestination(label: String, icon: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("nav:$label").clickable(enabled = enabled, onClick = onClick)
        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(60.dp, 32.dp).background(if (selected) Accent.copy(alpha = .14f)
            else MaterialTheme.colors.surface, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource("icons/$icon.svg"), null, tint = if (selected) Accent else Muted,
                modifier = Modifier.size(22.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Accent else Muted, maxLines = 1)
    }
}

/** Android tablet details keep artwork/metadata separate from the independently scrolling chapter list. */
@Composable
internal fun DesktopTabletDetails(controller: DesktopController, state: DesktopAppState) {
    val content = state.content ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Android's 1000dp threshold minus the 80dp rail and 2 × 24dp content padding.
        val expanded = maxWidth >= 872.dp
        val summary: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(content.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(content.authors.joinToString(" · ").ifBlank { "作者信息未提供" }, color = Muted)
                Text(state.selectedSource?.displayName ?: content.source.name, color = Accent, fontSize = 12.sp)
                Button({ controller.read() }, enabled = !state.busy && !content.chapters.isNullOrEmpty(),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (DesktopReaderKind.of(content) == DesktopReaderKind.VIDEO) "开始 / 继续播放" else "开始 / 继续阅读")
                }
                OutlinedButton({ controller.addFavourite() }, enabled = !state.busy && !state.isFavourite,
                    modifier = Modifier.fillMaxWidth()) { Text(if (state.isFavourite) "已收藏" else "加入收藏") }
            }
        }
        val metadata: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (expanded) {
                    DesktopCover(content, controller.session.covers, Modifier.width(180.dp).aspectRatio(2f / 3f)
                        .align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp))
                        .background(Accent.copy(alpha = .06f)), large = true)
                    summary(Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    DesktopCover(content, controller.session.covers, Modifier.width(140.dp).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = .06f)), large = true)
                    summary(Modifier.weight(1f))
                }
                content.description?.takeIf(String::isNotBlank)?.let {
                    Text(it.replace(Regex("<[^>]*>"), ""), color = Muted, fontSize = 14.sp)
                }
            }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("details-two-pane")) {
                Surface(Modifier.width(340.dp).fillMaxHeight(), shape = RoundedCornerShape(20.dp)) {
                    metadata(Modifier.fillMaxSize())
                }
                ChapterList(controller, state, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("details-stacked"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { metadata(Modifier.fillMaxWidth()) }
                item { Text("章节 · ${content.chapters?.size ?: 0}", fontWeight = FontWeight.SemiBold) }
                items(content.chapters.orEmpty(), key = { it.id }) { chapter -> ChapterRow(controller, state, chapter) }
            }
        }
    }
}

@Composable
private fun ChapterList(controller: DesktopController, state: DesktopAppState, modifier: Modifier) {
    var query by remember(state.content?.id) { mutableStateOf("") }
    var reversed by remember(state.content?.id) { mutableStateOf(false) }
    val chapters = remember(state.content?.chapters, query, reversed) {
        state.content?.chapters.orEmpty().filter { it.title.orEmpty().contains(query, true) }
            .let { if (reversed) it.asReversed() else it }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("章节 · ${state.content?.chapters?.size ?: 0}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton({ reversed = !reversed }) { Text(if (reversed) "倒序" else "正序") }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索章节") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("details-chapter-query"))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chapters, key = { it.id }) { chapter -> ChapterRow(controller, state, chapter) }
        }
    }
}

@Composable
private fun ChapterRow(controller: DesktopController, state: DesktopAppState,
    chapter: org.skepsun.kototoro.core.source.SourceChapter) {
    Surface(shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val video = DesktopReaderKind.of(state.content) == DesktopReaderKind.VIDEO
            Text(chapter.title ?: if (video) "第 ${chapter.number} 集" else "第 ${chapter.number} 章", modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Chapter downloads store page images; text and video chapters have no such download yet.
            if (DesktopReaderKind.of(state.content) == DesktopReaderKind.PAGES) {
                TextButton({ controller.downloadChapter(chapter) }, enabled = !state.busy,
                    modifier = Modifier.testTag("download-chapter:${chapter.id}")) { Text("下载") }
            }
            TextButton({ controller.read(chapter) }, enabled = !state.busy,
                modifier = Modifier.testTag("read-chapter:${chapter.id}")) { Text(if (video) "播放 →" else "阅读 →") }
        }
    }
}
