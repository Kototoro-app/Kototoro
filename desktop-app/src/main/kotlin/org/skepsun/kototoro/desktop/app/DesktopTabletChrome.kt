package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
    val destinations = listOf(TabletNavigationDestination(4, desktopText("nav.home")), TabletNavigationDestination(0, desktopText("nav.favourites")),
        TabletNavigationDestination(1, desktopText("nav.browse")), TabletNavigationDestination(2, desktopText("nav.history")),
        TabletNavigationDestination(5, desktopText("nav.subscriptions")), TabletNavigationDestination(3, desktopText("common.more")))
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

/** One language row: the chosen one is tinted, as the appearance buttons are. */
@Composable
private fun LanguageChoice(title: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton({ onClick() }, enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(containerColor =
            if (selected) Accent.copy(alpha = .12f) else Color.Transparent),
        modifier = Modifier.testTag(tag)) { Text(title) }
}

@Composable
internal fun DesktopMorePanel(controller: DesktopController, enabled: Boolean, onExitRequest: () -> Unit = {}) {
    val state by controller.state.collectAsState()
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MoreDestination(desktopText("settings.suggestions"), "ic_suggestion", desktopText("more.recommendationsSummary"),
            enabled, tag = "settings-recommendations-open") { controller.showRecommendations() }
        MoreDestination(desktopText("more.extensions"), "ic_extension", desktopText("more.extensionsSummary"), enabled) {
            controller.showExtensions()
        }
        MoreDestination(desktopText("more.downloads"), "ic_download", desktopText("more.downloadsSummary"), enabled) { controller.showDownloads() }
        MoreDestination(desktopText("more.backups"), "ic_backup_restore", desktopText("more.backupsSummary"), enabled) { controller.showBackups() }
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(desktopText("more.language"), style = MaterialTheme.typography.titleMedium)
                Text(desktopText("more.languageDetail"), color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageChoice(desktopText("more.languageSystem"), state.language == null, enabled,
                        "language:system") { controller.language(null) }
                    DesktopLanguages.bundled.forEach { language ->
                        LanguageChoice(language.nativeName, state.language == language.tag, enabled,
                            "language:${language.tag}") { controller.language(language.tag) }
                    }
                }
                Text(desktopText("more.appearance"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopAppearance.entries.forEach { appearance ->
                        OutlinedButton({ controller.appearance(appearance) }, enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor =
                                if (state.appearance == appearance) Accent.copy(alpha = .12f) else Color.Transparent),
                            modifier = Modifier.testTag("appearance:${appearance.name}")) { Text(appearance.title) }
                    }
                }
                Text(desktopText("more.interfaceStyle"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopInterfaceStyle.entries.forEach { style ->
                        OutlinedButton({ controller.interfaceStyle(style) }, enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor =
                                if (state.interfaceStyle == style) Accent.copy(alpha = .12f) else Color.Transparent),
                            modifier = Modifier.testTag("interface-style:${style.name}")) { Text(style.title) }
                    }
                }
            }
        }
        DesktopTrackerSettings(controller, state, enabled)
        DesktopAppUpdateSection(controller, state, enabled, onExitRequest)
        Divider(Modifier.padding(vertical = 12.dp))
        Text(desktopText("more.advancedTools"), fontWeight = FontWeight.SemiBold, color = Muted)
        MoreDestination(desktopText("more.browser"), "ic_web", desktopText("more.browserSummary"), enabled) { controller.browser() }
    }
}

@Composable
private fun MoreDestination(title: String, icon: String, description: String, enabled: Boolean, tag: String = "nav:$title",
    onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick)
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
    val directory = remember(content.id) { DesktopDetailsChapterState() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DesktopArtworkBackground(content, controller.session.covers, Modifier.matchParentSize())
        val expanded = tabletLayoutClass(LocalDesktopWindowWidth.current.value.toInt(), true) == TabletLayoutClass.EXPANDED
        val summary: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(content.title, style = if (expanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
                Text(content.authors.joinToString(" · ").ifBlank { desktopText("details.authorsUnknown") }, color = Muted)
                Text(state.sources.firstOrNull { it.source.name == content.source.name }?.displayName ?: content.source.name,
                    color = Accent, fontSize = 12.sp)
                Button({ controller.read() }, enabled = !state.busy && !content.chapters.isNullOrEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview-read")) {
                    Text(if (DesktopReaderKind.of(content) == DesktopReaderKind.VIDEO) desktopText("details.startWatching") else desktopText("details.startReading"))
                }
                OutlinedButton({ controller.toggleFavourite() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview-favourite")) {
                    Text(if (state.isFavourite) desktopText("details.unfavourite") else desktopText("details.favourite"))
                }
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
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(desktopText("details.synopsis"), style = MaterialTheme.typography.titleMedium)
                            Text(it.replace(Regex("<[^>]*>"), ""), color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                DesktopDetailsInfo(content, enabled = !state.busy)
            }
        }
        var tab by remember(content.id) { mutableStateOf(DesktopDetailsTab.CHAPTERS) }
        val bookmarks = remember(state.bookmarks, content.id) { state.bookmarks.count { it.contentId == content.id } }
        val tabs: @Composable (Modifier) -> Unit = { tabsModifier ->
            Row(tabsModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopDetailsTab.entries.forEach { candidate ->
                    val label = when (candidate) {
                        DesktopDetailsTab.CHAPTERS -> desktopText("details.chapters", content.chapters?.size ?: 0)
                        DesktopDetailsTab.BOOKMARKS -> desktopText("details.tabBookmarks", bookmarks)
                    }
                    OutlinedButton({ tab = candidate }, enabled = !state.busy,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor =
                            if (tab == candidate) Accent.copy(alpha = .12f) else Color.Transparent),
                        modifier = Modifier.weight(1f, fill = false).testTag("details-tab:${candidate.name}")) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (expanded) {
            TabletDetailsPanes(Modifier.fillMaxSize().testTag("details-two-pane"),
                summary = { paneModifier ->
                    Surface(paneModifier, shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) { metadata(Modifier.fillMaxSize()) }
                },
                chapters = { paneModifier ->
                    Column(paneModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tabs(Modifier.fillMaxWidth())
                        when (tab) {
                            DesktopDetailsTab.CHAPTERS -> DesktopDetailsChapters(controller, state, directory,
                                Modifier.weight(1f))
                            DesktopDetailsTab.BOOKMARKS -> DesktopDetailsBookmarks(controller, state,
                                Modifier.weight(1f))
                        }
                    }
                },
            )
        } else {
            Column(Modifier.fillMaxSize().testTag("details-stacked"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tabs(Modifier.fillMaxWidth())
                when (tab) {
                    DesktopDetailsTab.CHAPTERS -> DesktopDetailsChapters(controller, state, directory,
                        Modifier.weight(1f), leadingContent = { metadata(Modifier.fillMaxWidth()) })
                    DesktopDetailsTab.BOOKMARKS -> DesktopDetailsBookmarks(controller, state, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Android's details tabs; the Windows panes switch between the same two lists instead of scrolling past both. */
internal enum class DesktopDetailsTab { CHAPTERS, BOOKMARKS }

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
        .filter { query.isEmpty() || chapterMatchesQuery(query, it.title,
            it.numberLabel(), it.volume.takeIf { volume -> volume > 0 }?.toString()) }
    return DesktopChapterList(options, branchChapters.firstOrNull()?.branch.takeIf { options.isNotEmpty() },
        branchChapters.size, withVolumeSections(visible, SourceChapter::volume, SourceChapter::scanlator))
}
