package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.adaptive.tabletLayoutClass
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.home.*
import org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardBookSpine
import org.skepsun.kototoro.list.ui.compose.ContentCardBottomProgressBar
import org.skepsun.kototoro.list.ui.compose.TabletPosterCover
import org.skepsun.kototoro.list.ui.compose.rememberCoverRimBorderBrush

/** One work on the home page with what its row shows. */
private data class HomeWork(val content: SourceContent, val progress: Float? = null, val newChapters: Int = 0)

private fun HomeHeroKind.label() = when (this) {
    HomeHeroKind.RESUME -> "继续阅读"
    HomeHeroKind.HISTORY -> "最近阅读"
    HomeHeroKind.UPDATE -> "最近更新"
    HomeHeroKind.RECOMMENDATION -> "推荐"
}

private fun HomeHeroKind.icon() = when (this) {
    HomeHeroKind.RESUME -> "icons/ic_read.svg"
    HomeHeroKind.HISTORY -> "icons/ic_history.svg"
    HomeHeroKind.UPDATE -> "icons/ic_updated.svg"
    HomeHeroKind.RECOMMENDATION -> "icons/ic_suggestion.svg"
}

/**
 * Android's tablet home on shared components: hero pager, history and updates rails, and quick access.
 * Recommendations come from the shared suggestion rules (DesktopSuggestions), refreshed on demand.
 */
@Composable
internal fun DesktopHomePanel(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    val filter = state.homeFilter
    val history = remember(state.homeHistory, filter, ecosystems) {
        state.homeHistory.entries
            .filter { filter.accepts(ecosystems[it.content.source.name], it.content.source.contentType) }
            .sortedByDescending { it.lastReadAt ?: it.updatedAt }
            .map { HomeWork(it.content, it.progressPercent) }
    }
    val updates = remember(state.feed, state.feedContents, filter, ecosystems) {
        state.feed.updateRowsByOwnerId.values
            .sortedByDescending { it.lastChapterDate ?: it.lastCheckTime }
            .mapNotNull { row -> state.feedContents[row.displayMangaId ?: row.mangaId]?.let { HomeWork(it, newChapters = row.newChapters) } }
            .filter { filter.accepts(ecosystems[it.content.source.name], it.content.source.contentType) }
    }
    val recommendations = remember(state.suggestions, filter, ecosystems) {
        state.suggestions.filter { filter.accepts(ecosystems[it.source.name], it.source.contentType) }.map { HomeWork(it) }
    }
    val hero = remember(history, updates, recommendations) {
        buildHomeHeroEntries(
            resume = history.firstOrNull(),
            resumeGroupKey = history.firstOrNull()?.content?.id,
            resumeProgressPercent = history.firstOrNull()?.progress?.let { (it * 100).toInt() },
            resumeKeyOf = { it.content.id },
            history = history.map { HomeHeroCandidate(it, it.content.id) },
            updates = updates.map { HomeHeroCandidate(it, it.content.id, it.newChapters) },
            recommendations = recommendations.map { HomeHeroCandidate(it, it.content.id) },
        )
    }
    val open: (SourceContent) -> Unit = { if (enabled) controller.details(it) }
    Column(Modifier.fillMaxSize().testTag("home-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBarTitleBlock("主页")
            Spacer(Modifier.weight(1f))
            DesktopSourceFilterControls(filter, state.sources, controller::homeFilter, enabled, "home")
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val layoutClass = tabletLayoutClass(LocalDesktopWindowWidth.current.value.toInt(), true)
            val viewport = maxWidth
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hero.isNotEmpty()) HomeHero(controller, hero, viewport, layoutClass, open)
                val sections: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (history.isNotEmpty()) HistoryRail(controller, history, open)
                        if (updates.isNotEmpty()) UpdatesRail(controller, updates)
                        RecommendationsRail(controller, recommendations, state.suggestionSettings.enabled, enabled, open)
                        if (history.isEmpty() && updates.isEmpty()) Text("阅读或收藏作品后，主页会显示历史和更新。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val quickAccess: @Composable (Modifier) -> Unit = { modifier ->
                    QuickAccess(controller, state, enabled, modifier)
                }
                // Android's tablet home puts quick access in a side column on expanded windows.
                if (layoutClass == TabletLayoutClass.EXPANDED) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    sections(Modifier.weight(0.64f))
                    quickAccess(Modifier.weight(0.36f))
                } else {
                    sections(Modifier.fillMaxWidth())
                    quickAccess(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun HomeHero(controller: DesktopController, entries: List<HomeHeroEntry<HomeWork>>, viewport: androidx.compose.ui.unit.Dp,
    layoutClass: TabletLayoutClass, open: (SourceContent) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { entries.size })
    val spacing = 6.dp
    val cardWidth = homeHeroCardWidth(viewport, 0.dp, spacing, layoutClass)
    HorizontalPager(pagerState, pageSize = PageSize.Fixed(cardWidth), pageSpacing = spacing,
        modifier = Modifier.fillMaxWidth().testTag("home-hero")) { page ->
        val entry = entries[page]
        val content = entry.content.content
        Box(Modifier.fillMaxWidth().height(HomeHeroCardHeight).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable { open(content) }
            .testTag("home-hero:$page")) {
            DesktopCover(content, controller.session.covers, Modifier.fillMaxSize().blur(24.dp), large = true)
            Box(Modifier.fillMaxSize().drawBehind { drawRect(HomeHeroArtworkScrim) })
            Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DesktopCover(content, controller.session.covers, Modifier.size(72.dp, 112.dp).clip(RoundedCornerShape(8.dp)))
                HomeHeroText(
                    kindLabel = entry.kind.label(),
                    kindIcon = painterResource(entry.kind.icon()),
                    title = content.title,
                    sourceTitle = controller.state.value.sources.firstOrNull { it.source.name == content.source.name }
                        ?.displayName ?: content.source.name,
                    supportingText = entry.newChapters.takeIf { it > 0 }?.let { "新章节 $it" }
                        ?: entry.progressPercent?.let { "已读 $it%" },
                    compact = true,
                    textColor = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            if (page == pagerState.currentPage && entries.size > 1) HeroPagerIndicator(
                pageCount = entries.size, currentPage = pagerState.currentPage,
                activeColor = Color.White.copy(alpha = 0.92f), inactiveColor = Color.White.copy(alpha = 0.38f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(start = 14.dp, end = 14.dp, bottom = 7.dp),
            )
        }
    }
}

@Composable
private fun HistoryRail(controller: DesktopController, works: List<HomeWork>, open: (SourceContent) -> Unit) {
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rim = rememberCoverRimBorderBrush(isIosStyle, isDark = !androidx.compose.material.MaterialTheme.colors.isLight)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader("历史", works.size, painterResource("icons/ic_arrow_forward.svg"),
            onMoreClick = { controller.library(true) }, titleModifier = Modifier.testTag("home-history-more"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("home-history")) {
            items(works.take(20), key = { it.content.id }) { work ->
                val style = CompactPosterCardStyle(112.dp, 158.dp, 12.dp)
                TabletPosterCover(work.content.title, style, rim,
                    modifier = Modifier.width(112.dp).height(158.dp).clickable { open(work.content) }
                        .testTag("home-history:${work.content.id}"),
                    cover = { DesktopCover(work.content, controller.session.covers, Modifier.matchParentSize()) },
                    progress = {
                        work.progress?.takeIf { ReadingProgress.isValid(it) }?.let {
                            ContentCardBottomProgressBar(it, ReadingProgress.isCompleted(it), Modifier.align(Alignment.BottomCenter))
                        }
                    })
            }
        }
    }
}

@Composable
private fun UpdatesRail(controller: DesktopController, works: List<HomeWork>) {
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rim = rememberCoverRimBorderBrush(isIosStyle, isDark = !androidx.compose.material.MaterialTheme.colors.isLight)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader("更新", works.size, painterResource("icons/ic_arrow_forward.svg"),
            onMoreClick = { controller.subscriptions() }, titleModifier = Modifier.testTag("home-updates-more"))
        // Android's list-mode rail: rows of covers with the new chapter count, three to a row on wide windows.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 300.dp).toInt().coerceIn(1, 3)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                works.take(columns * 2).chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { work ->
                            HomeListRailRow(
                                title = work.content.title,
                                sourceTitle = controller.state.value.sources.firstOrNull { it.source.name == work.content.source.name }
                                    ?.displayName ?: work.content.source.name,
                                coverWidth = 64.dp,
                                coverShape = RoundedCornerShape(10.dp),
                                rimBorderBrush = rim,
                                onClick = { controller.openTracked(work.content.id) },
                                supportingText = "新章节 ${work.newChapters}",
                                modifier = Modifier.weight(1f).testTag("home-update:${work.content.id}"),
                                cover = {
                                    DesktopCover(work.content, controller.session.covers, Modifier.matchParentSize())
                                    ContentCardBookSpine(Modifier.align(Alignment.CenterStart), width = 2.5.dp)
                                },
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAccess(controller: DesktopController, state: DesktopAppState, enabled: Boolean, modifier: Modifier) {
    val icon: @Composable (String) -> androidx.compose.ui.graphics.painter.Painter = { painterResource("icons/$it.svg") }
    val actions = listOf(
        HomeQuickAction("收藏", icon("ic_heart"), { controller.library() }, enabled),
        HomeQuickAction("历史", icon("ic_history"), { controller.library(true) }, enabled),
        HomeQuickAction("订阅", icon("ic_feed"), { controller.subscriptions() }, enabled),
        HomeQuickAction("下载", icon("ic_download"), { controller.showDownloads() }, enabled),
        HomeQuickAction("随机", icon("ic_dice"), { controller.randomFavourite() }, enabled),
        HomeQuickAction("拓展", icon("ic_extension"), { controller.showExtensions() }, enabled),
        HomeQuickAction("备份", icon("ic_backup_restore"), { controller.showBackups() }, enabled),
        HomeQuickAction("设置", icon("ic_settings"), { controller.more() }, enabled),
    )
    HomeQuickActionsGrid("快捷入口", actions, modifier.testTag("home-quick-access"),
        preferredTileWidth = 96.dp, isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS,
        tileModifier = { index, _ -> Modifier.testTag("home-quick:$index") })
}

@Composable
private fun RecommendationsRail(controller: DesktopController, works: List<HomeWork>, suggestionsEnabled: Boolean, enabled: Boolean,
    open: (SourceContent) -> Unit) {
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rim = rememberCoverRimBorderBrush(isIosStyle, isDark = !androidx.compose.material.MaterialTheme.colors.isLight)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader("推荐", works.size, painterResource("icons/ic_arrow_forward.svg"),
            onMoreClick = { if (enabled) controller.refreshSuggestions() }, titleModifier = Modifier.testTag("home-recommendations-more"),
            configureIcon = painterResource("icons/ic_suggestion.svg"), configureLabel = "刷新推荐",
            configureModifier = Modifier.testTag("home-recommendations-refresh"),
            onConfigureClick = { if (enabled) controller.refreshSuggestions() })
        if (works.isEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("home-recommendations-empty")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (suggestionsEnabled) "根据阅读历史和收藏的标签，从已安装的来源中挑选作品。"
                        else "推荐尚未开启。开启后每 6 小时根据阅读历史和收藏的标签生成一次。",
                        modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ if (suggestionsEnabled) controller.refreshSuggestions() else controller.enableSuggestions() },
                        enabled = enabled, modifier = Modifier.testTag("home-recommendations-generate")) {
                        Text(if (suggestionsEnabled) "生成推荐" else "开启推荐")
                    }
                }
            }
        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("home-recommendations")) {
            items(works.take(30), key = { it.content.id }) { work ->
                val style = CompactPosterCardStyle(112.dp, 158.dp, 12.dp)
                TabletPosterCover(work.content.title, style, rim,
                    modifier = Modifier.width(112.dp).height(158.dp).clickable { open(work.content) }
                        .testTag("home-recommendation:${work.content.id}"),
                    cover = { DesktopCover(work.content, controller.session.covers, Modifier.matchParentSize()) })
            }
        }
    }
}