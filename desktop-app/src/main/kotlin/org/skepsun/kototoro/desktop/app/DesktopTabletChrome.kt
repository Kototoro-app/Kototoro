package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.ui.adaptive.*
import org.skepsun.kototoro.core.ui.chapters.*

/** Windows supplies destination actions and artwork to the shared Android tablet rail. */
@Composable
internal fun DesktopNavigationRail(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    // Android's tablet order: home, favourites, browse, history, subscriptions (then Windows' "more").
    val destinations = listOf(TabletNavigationDestination(4, "主页"), TabletNavigationDestination(0, "收藏"),
        TabletNavigationDestination(1, "浏览"), TabletNavigationDestination(2, "历史"),
        TabletNavigationDestination(5, "订阅"), TabletNavigationDestination(3, "更多"))
    val selected = when (state.screen) {
        DesktopScreen.HOME -> 4
        DesktopScreen.FEED -> 5
        DesktopScreen.LIBRARY -> 0
        DesktopScreen.EXPLORE, DesktopScreen.DETAILS, DesktopScreen.PREFERENCES -> 1
        DesktopScreen.HISTORY -> 2
        else -> 3
    }
    val updates = state.feed.updateRowsByOwnerId.size
    val navigate: (Int) -> Unit = { id -> when (id) {
        4 -> controller.home()
        5 -> controller.subscriptions()
        0 -> controller.library()
        1 -> controller.explore()
        2 -> controller.library(true)
        else -> controller.more()
    } }
    // Re-selecting "browse" inside a source goes back to the source grid, like Android's tab re-tap.
    val reselect: (Int) -> Unit = { id ->
        if (id == 1 && state.screen == DesktopScreen.EXPLORE && state.selectedSource != null) controller.exitSource()
        else navigate(id)
    }
    androidx.compose.material3.Surface(Modifier.width(80.dp).fillMaxHeight().testTag("tablet-navigation"),
        color = androidx.compose.material3.NavigationRailDefaults.ContainerColor, tonalElevation = 3.dp) {
        TabletNavigationRail(destinations, selected, navigate, reselect,
            icon = { id, isSelected ->
                val resource = when (id) {
                    4 -> "ic_home"
                    5 -> "ic_feed"
                    0 -> if (isSelected) "ic_heart" else "ic_heart_outline"
                    1 -> "ic_explore_normal"
                    2 -> "ic_history"
                    else -> "ic_more_vert"
                }
                androidx.compose.material3.BadgedBox(badge = {
                    if (id == 5 && updates > 0) androidx.compose.material3.Badge(Modifier.testTag("nav-feed-badge")) {
                        androidx.compose.material3.Text(if (updates > 99) "99+" else updates.toString())
                    }
                }) {
                    androidx.compose.material3.Icon(painterResource("icons/$resource.svg"), null,
                        modifier = Modifier.size(24.dp))
                }
            },
            modifier = Modifier.fillMaxSize(), enabled = enabled,
            selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            itemModifier = { id -> Modifier.testTag("nav:${destinations.first { it.id == id }.title}") },
        )
    }
}

@Composable
internal fun DesktopMorePanel(controller: DesktopController, enabled: Boolean) {
    val state by controller.state.collectAsState()
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MoreDestination("扩展与仓库", "ic_extension", "管理来源、添加仓库与导入本地扩展", enabled) {
            controller.showExtensions()
        }
        MoreDestination("下载", "ic_download", "查看离线章节与下载任务", enabled) { controller.showDownloads() }
        MoreDestination("备份与恢复", "ic_backup_restore", "导出书库，或预览并合并备份", enabled) { controller.showBackups() }
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("外观", style = MaterialTheme.typography.subtitle1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopAppearance.entries.forEach { appearance ->
                        OutlinedButton({ controller.appearance(appearance) }, enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor =
                                if (state.appearance == appearance) Accent.copy(alpha = .12f) else Color.Transparent),
                            modifier = Modifier.testTag("appearance:${appearance.name}")) { Text(appearance.title) }
                    }
                }
                Text("界面风格", style = MaterialTheme.typography.subtitle1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopInterfaceStyle.entries.forEach { style ->
                        OutlinedButton({ controller.interfaceStyle(style) }, enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor =
                                if (state.interfaceStyle == style) Accent.copy(alpha = .12f) else Color.Transparent),
                            modifier = Modifier.testTag("interface-style:${style.name}")) { Text(style.title) }
                    }
                }
            }
        }
        DesktopBackgroundSettings(controller, state, enabled)
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

/** Android tablet details keep artwork/metadata separate from the independently scrolling chapter list. */
@Composable
internal fun DesktopTabletDetails(controller: DesktopController, state: DesktopAppState) {
    val content = state.content ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DesktopArtworkBackground(content, controller.session.covers, Modifier.matchParentSize())
        val expanded = tabletLayoutClass(LocalDesktopWindowWidth.current.value.toInt(), true) == TabletLayoutClass.EXPANDED
        val summary: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(content.title, style = if (expanded) MaterialTheme.typography.h4 else MaterialTheme.typography.h6)
                Text(content.authors.joinToString(" · ").ifBlank { "作者信息未提供" }, color = Muted)
                Text(state.sources.firstOrNull { it.source.name == content.source.name }?.displayName ?: content.source.name,
                    color = Accent, fontSize = 12.sp)
                Button({ controller.read() }, enabled = !state.busy && !content.chapters.isNullOrEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview-read")) {
                    Text(if (DesktopReaderKind.of(content) == DesktopReaderKind.VIDEO) "开始 / 继续播放" else "开始 / 继续阅读")
                }
                OutlinedButton({ controller.addFavourite() }, enabled = !state.busy && !state.isFavourite,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview-favourite")) { Text(if (state.isFavourite) "已收藏" else "加入收藏") }
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
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colors.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("简介", style = MaterialTheme.typography.subtitle1)
                            Text(it.replace(Regex("<[^>]*>"), ""), color = Muted, style = MaterialTheme.typography.body2)
                        }
                    }
                }
            }
        }
        if (expanded) {
            TabletDetailsPanes(Modifier.fillMaxSize().testTag("details-two-pane"),
                summary = { paneModifier ->
                    Surface(paneModifier, shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colors.surface.copy(alpha = .92f)) { metadata(Modifier.fillMaxSize()) }
                },
                chapters = { paneModifier -> ChapterList(controller, state, paneModifier) },
            )
        } else {
            val list = remember(content.chapters, state.chapterBranch) {
                desktopChapterList(content.chapters.orEmpty(), state.chapterBranch, "", false)
            }
            LazyColumn(Modifier.fillMaxSize().testTag("details-stacked"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { metadata(Modifier.fillMaxWidth()) }
                item { Text("章节 · ${list.count}", fontWeight = FontWeight.SemiBold) }
                item { DesktopChapterBranches(controller, state, list) }
                chapterSections(controller, state, list)
            }
        }
    }
}

/** Android's chapter list pipeline: one branch, then order and search, then volume headers on what remains. */
internal data class DesktopChapterList(
    val options: List<ChapterBranchOption>,
    val selectedBranch: String?,
    val count: Int,
    val sections: List<ChapterSection<SourceChapter>>,
)

internal fun desktopChapterList(chapters: List<SourceChapter>, branch: String?, query: String,
    reversed: Boolean): DesktopChapterList {
    val collator = java.text.Collator.getInstance()
    val options = chapterBranchOptions(chapters, SourceChapter::branch, nullsFirst(Comparator { a, b -> collator.compare(a, b) }))
    val branchChapters = chaptersOfBranch(chapters, SourceChapter::branch, branch)
    val visible = branchChapters.let { if (reversed) it.asReversed() else it }
        .filter { it.title.orEmpty().contains(query, true) }
    return DesktopChapterList(options, branchChapters.firstOrNull()?.branch.takeIf { options.isNotEmpty() },
        branchChapters.size, withVolumeSections(visible, SourceChapter::volume, SourceChapter::scanlator))
}

private fun ChapterSection.Header.title(): String = customName ?: if (volume <= 0) "未知卷" else "第 $volume 卷"

@Composable
private fun DesktopChapterBranches(controller: DesktopController, state: DesktopAppState, list: DesktopChapterList) {
    ChapterBranchChips(list.options, list.selectedBranch, title = { it ?: "默认" },
        onSelect = controller::selectChapterBranch, enabled = !state.busy,
        chipModifier = { Modifier.testTag("chapter-branch:${it.name ?: "default"}") })
}

private fun androidx.compose.foundation.lazy.LazyListScope.chapterSections(controller: DesktopController,
    state: DesktopAppState, list: DesktopChapterList) {
    items(list.sections.size, key = { index ->
        when (val section = list.sections[index]) {
            is ChapterSection.Header -> "header:$index:${section.title()}"
            is ChapterSection.Item -> section.chapter.id
        }
    }) { index ->
        when (val section = list.sections[index]) {
            is ChapterSection.Header -> ChapterSectionHeader(section.title(),
                Modifier.testTag("chapter-header:${section.title()}"))
            is ChapterSection.Item -> ChapterRow(controller, state, section.chapter)
        }
    }
}

@Composable
private fun ChapterList(controller: DesktopController, state: DesktopAppState, modifier: Modifier) {
    var query by remember(state.content?.id) { mutableStateOf("") }
    var reversed by remember(state.content?.id) { mutableStateOf(false) }
    val list = remember(state.content?.chapters, state.chapterBranch, query, reversed) {
        desktopChapterList(state.content?.chapters.orEmpty(), state.chapterBranch, query, reversed)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("章节 · ${list.count}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton({ reversed = !reversed }) { Text(if (reversed) "倒序" else "正序") }
        }
        DesktopChapterBranches(controller, state, list)
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索章节") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("details-chapter-query"))
        LazyColumn(Modifier.weight(1f).testTag("details-chapter-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chapterSections(controller, state, list)
        }
    }
}

@Composable
private fun ChapterRow(controller: DesktopController, state: DesktopAppState, chapter: SourceChapter) {
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
