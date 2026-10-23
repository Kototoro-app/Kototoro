package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.home.*
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll
import org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardBookSpine
import org.skepsun.kototoro.list.ui.compose.ContentCardBottomProgressBar
import org.skepsun.kototoro.list.ui.compose.TabletPosterCover
import org.skepsun.kototoro.list.ui.compose.rememberCoverRimBorderBrush

/** One work on the home page with what its row shows. */
private data class HomeWork(val content: SourceContent, val progress: Float? = null, val newChapters: Int = 0)

private fun HomeHeroKind.label() = when (this) {
    HomeHeroKind.RESUME -> desktopText("home.hero.resume")
    HomeHeroKind.HISTORY -> desktopText("home.hero.history")
    HomeHeroKind.UPDATE -> desktopText("home.hero.update")
    HomeHeroKind.RECOMMENDATION -> desktopText("home.hero.recommendation")
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
    // Android's SuggestionsViewModel and UpdatesViewModel both drop works whose tags are blacklisted globally.
    val blacklist = remember(state.sourceSettings.tagBlacklist) { state.sourceSettings.tagBlacklistRules() }
    val hidden: (SourceContent) -> Boolean = { content ->
        content.tags.any { blacklist.containsTagTitle(it.title) }
    }
    val updates = remember(state.feed, state.feedContents, filter, ecosystems, blacklist) {
        state.feed.updateRowsByOwnerId.values
            .sortedByDescending { it.lastChapterDate ?: it.lastCheckTime }
            .mapNotNull { row ->
                state.feedContents[row.displayMangaId ?: row.mangaId]?.let { HomeWork(it, newChapters = row.newChapters) }
            }
            .filter { filter.accepts(ecosystems[it.content.source.name], it.content.source.contentType) }
            .filterNot { hidden(it.content) }
    }
    val recommendations = remember(state.suggestions, filter, ecosystems, blacklist) {
        state.suggestions
            .filter { filter.accepts(ecosystems[it.source.name], it.source.contentType) }
            .filterNot(hidden)
            .map { HomeWork(it) }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBarTitleBlock(desktopText("nav.home"))
            Spacer(Modifier.weight(1f))
            DesktopSourceFilterControls(filter, state.sources, controller::homeFilter, enabled, "home")
        }
        HomeScreenLayout(
            hasHero = hero.isNotEmpty(),
            hasHighlights = true,
            initialized = true,
            tabletLayoutEnabled = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            estimatedHeroHeight = HomeHeroCardHeight + 8.dp,
            hero = { modifier, layoutClass ->
                Box(modifier.fillMaxWidth().padding(top = 8.dp)) {
                    HomeHero(controller, hero, layoutClass, open)
                }
            },
            highlights = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HomeScreenSectionGap)) {
                    if (history.isNotEmpty()) HistoryRail(controller, history, open)
                    if (updates.isNotEmpty()) UpdatesRail(controller, updates)
                    RecommendationsRail(controller, recommendations, state.suggestionSettings.enabled,
                        state.suggestionsGenerating, enabled, open)
                    if (history.isEmpty() && updates.isEmpty()) Text(desktopText("home.empty"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            quickActions = { expanded -> QuickAccess(controller, enabled, expanded) },
        )
    }
}

@Composable
private fun HomeHero(controller: DesktopController, entries: List<HomeHeroEntry<HomeWork>>,
    layoutClass: TabletLayoutClass, open: (SourceContent) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { entries.size })
    HomeHeroPager(
        entries = entries,
        key = { "${it.kind}:${it.groupKey}" },
        pagerState = pagerState,
        layoutClass = layoutClass,
        modifier = Modifier.testTag("home-hero"),
    ) { page, entry, cardModifier ->
        val content = entry.content.content
        HomeHeroCardSurface(
            onClick = { open(content) },
            modifier = cardModifier.testTag("home-hero:$page"),
            background = {
                DesktopCover(content, controller.session.covers, Modifier.fillMaxSize().blur(24.dp), large = true)
            },
        ) {
            HomeHeroStandardContent(
                poster = { width, height ->
                    DesktopCover(content, controller.session.covers,
                        Modifier.size(width, height).clip(RoundedCornerShape(8.dp)))
                },
                text = { textModifier ->
                    HomeHeroText(
                        kindLabel = entry.kind.label(),
                        kindIcon = painterResource(entry.kind.icon()),
                        title = content.title,
                        sourceTitle = controller.state.value.sources.firstOrNull { it.source.name == content.source.name }
                            ?.displayName ?: content.source.name,
                        supportingText = entry.newChapters.takeIf { it > 0 }?.let { desktopText("home.newChapters", it) }
                            ?: entry.progressPercent?.let { desktopText("home.readPercent", it) },
                        compact = true,
                        textColor = Color.White,
                        modifier = textModifier,
                    )
                },
            )
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
    val listState = rememberLazyListState()
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rim = rememberCoverRimBorderBrush(isIosStyle, isDark = MaterialTheme.colorScheme.background.luminance() < .5f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader(desktopText("home.sectionHistory"), works.size, painterResource("icons/ic_arrow_forward.svg"),
            onMoreClick = { controller.library(true) }, titleModifier = Modifier.testTag("home-history-more"))
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.mouseDragScroll(listState, Orientation.Horizontal).testTag("home-history")) {
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
    val rim = rememberCoverRimBorderBrush(isIosStyle, isDark = MaterialTheme.colorScheme.background.luminance() < .5f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader(desktopText("home.sectionUpdates"), works.size, painterResource("icons/ic_arrow_forward.svg"),
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
                                supportingText = desktopText("home.newChapters", work.newChapters),
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
private fun QuickAccess(controller: DesktopController, enabled: Boolean, expanded: Boolean) {
    val icon: @Composable (String) -> androidx.compose.ui.graphics.painter.Painter = { painterResource("icons/$it.svg") }
    val actions = listOf(
        HomeQuickAction(desktopText("home.quick.favourites"), icon("ic_heart"), { controller.library() }, enabled),
        HomeQuickAction(desktopText("home.quick.history"), icon("ic_history"), { controller.library(true) }, enabled),
        HomeQuickAction(desktopText("home.quick.subscriptions"), icon("ic_feed"), { controller.subscriptions() }, enabled),
        HomeQuickAction(desktopText("home.quick.downloads"), icon("ic_download"), { controller.showDownloads() }, enabled),
        HomeQuickAction(desktopText("home.quick.random"), icon("ic_dice"), { controller.randomFavourite() }, enabled),
        HomeQuickAction(desktopText("home.quick.extensions"), icon("ic_extension"), { controller.showExtensions() }, enabled),
        HomeQuickAction(desktopText("home.quick.backups"), icon("ic_backup_restore"), { controller.showBackups() }, enabled),
        HomeQuickAction(desktopText("home.quick.settings"), icon("ic_settings"), { controller.more() }, enabled),
    )
    HomeQuickActionsGrid(desktopText("home.quickAccess"), actions, Modifier.fillMaxWidth().testTag("home-quick-access"),
        preferredTileWidth = if (expanded) 96.dp else 68.dp, isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS,
        tileModifier = { index, _ -> Modifier.testTag("home-quick:$index") })
}

@Composable
private fun RecommendationsRail(controller: DesktopController, works: List<HomeWork>, suggestionsEnabled: Boolean,
    generating: Boolean, enabled: Boolean, open: (SourceContent) -> Unit) {
    val listState = rememberLazyListState()
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rim = rememberCoverRimBorderBrush(isIosStyle, isDark = MaterialTheme.colorScheme.background.luminance() < .5f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader(desktopText("home.sectionRecommendations"), works.size, painterResource("icons/ic_arrow_forward.svg"),
            onMoreClick = { if (enabled && !generating) controller.refreshSuggestions() },
            titleModifier = Modifier.testTag("home-recommendations-more"),
            configureIcon = painterResource("icons/ic_suggestion.svg"), configureLabel = desktopText("home.refreshRecommendations"),
            configureModifier = Modifier.testTag("home-recommendations-refresh"),
            onConfigureClick = { if (enabled && !generating) controller.refreshSuggestions() })
        if (works.isEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("home-recommendations-empty")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (generating) desktopText("message.generatingSuggestions")
                        else if (suggestionsEnabled) desktopText("home.suggestionsDetail")
                        else desktopText("home.suggestionsOffDetail"),
                        modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ if (suggestionsEnabled) controller.refreshSuggestions() else controller.enableSuggestions() },
                        enabled = enabled && !generating, modifier = Modifier.testTag("home-recommendations-generate")) {
                        Text(if (suggestionsEnabled) desktopText("home.generateSuggestions") else desktopText("home.enableSuggestions"))
                    }
                }
            }
        } else LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.mouseDragScroll(listState, Orientation.Horizontal, enabled = enabled)
                .testTag("home-recommendations")) {
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
