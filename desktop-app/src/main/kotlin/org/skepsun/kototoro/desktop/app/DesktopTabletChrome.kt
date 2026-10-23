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
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

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

/**
 * Android's "更多" tab is a settings page: titled sections, one card each, and every entry a row. The Windows host keeps
 * its own entry points (recommendations, extensions, downloads, backups, browser) but draws them the same way, with a
 * leading icon and the current value in front of the chevron; the language/appearance/style chips become choice dialogs.
 */
@Composable
internal fun DesktopMorePanel(controller: DesktopController, enabled: Boolean, onExitRequest: () -> Unit = {}) {
    val state by controller.state.collectAsState()
    var dialog by remember { mutableStateOf<DesktopMoreDialog?>(null) }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(desktopText("more.features"))
        SettingsGroup {
            SettingsValueRow(desktopText("screens.readerSettings"), desktopText("reader.settingsSummary"), null, enabled,
                { controller.showReaderSettings() }, testTag = "reader-settings-open",
                leadingIcon = painterResource("icons/ic_read.svg"))
            SettingsRowDivider()
            SettingsValueRow(desktopText("screens.sourceSettings"), desktopText("sources.sortSummary"), null, enabled,
                { controller.showSourceSettings() }, testTag = "source-settings-open",
                leadingIcon = painterResource("icons/ic_source_builtin.svg"))
            SettingsRowDivider()
            SettingsValueRow(desktopText("settings.suggestions"), desktopText("more.recommendationsSummary"), null,
                enabled, { controller.showRecommendations() }, testTag = "settings-recommendations-open",
                leadingIcon = painterResource("icons/ic_suggestion.svg"))
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.extensions"), desktopText("more.extensionsSummary"), null, enabled,
                { controller.showExtensions() }, testTag = "nav:${desktopText("more.extensions")}",
                leadingIcon = painterResource("icons/ic_extension.svg"))
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.downloads"), desktopText("more.downloadsSummary"), null, enabled,
                { controller.showDownloads() }, testTag = "nav:${desktopText("more.downloads")}",
                leadingIcon = painterResource("icons/ic_download.svg"))
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.backups"), desktopText("more.backupsSummary"), null, enabled,
                { controller.showBackups() }, testTag = "nav:${desktopText("more.backups")}",
                leadingIcon = painterResource("icons/ic_backup_restore.svg"))
        }
        SettingsSectionHeader(desktopText("more.appearance"))
        SettingsGroup {
            SettingsValueRow(desktopText("more.theme"), null, state.appearance.title, enabled,
                { dialog = DesktopMoreDialog.THEME }, testTag = "appearance")
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.interfaceStyle"), null, state.interfaceStyle.title, enabled,
                { dialog = DesktopMoreDialog.INTERFACE_STYLE }, testTag = "interface-style")
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.language"), desktopText("more.languageDetail"),
                state.language?.let { tag -> DesktopLanguages.bundled.firstOrNull { it.tag == tag }?.nativeName ?: tag }
                    ?: desktopText("more.languageSystem"),
                enabled, { dialog = DesktopMoreDialog.LANGUAGE }, testTag = "language")
        }
        DesktopTrackerSettings(controller, state, enabled)
        DesktopAppUpdateSection(controller, state, enabled, onExitRequest)
        SettingsSectionHeader(desktopText("more.advancedTools"))
        SettingsGroup {
            SettingsValueRow(desktopText("more.browser"), desktopText("more.browserSummary"), null, enabled,
                { controller.browser() }, testTag = "nav:${desktopText("more.browser")}",
                leadingIcon = painterResource("icons/ic_web.svg"))
        }
    }
    when (dialog) {
        DesktopMoreDialog.THEME -> DesktopChoiceSettingsDialog(
            title = desktopText("more.theme"),
            summary = null,
            bodyTag = "appearance-dialog",
            optionTagPrefix = "appearance",
            options = DesktopAppearance.entries.map { it.title to it.name },
            selected = state.appearance.name,
            enabled = enabled,
            onPick = { name -> controller.appearance(DesktopAppearance.valueOf(name)); dialog = null },
            onDismiss = { dialog = null },
        )
        DesktopMoreDialog.INTERFACE_STYLE -> DesktopChoiceSettingsDialog(
            title = desktopText("more.interfaceStyle"),
            summary = null,
            bodyTag = "interface-style-dialog",
            optionTagPrefix = "interface-style",
            options = DesktopInterfaceStyle.entries.map { it.title to it.name },
            selected = state.interfaceStyle.name,
            enabled = enabled,
            onPick = { name -> controller.interfaceStyle(DesktopInterfaceStyle.valueOf(name)); dialog = null },
            onDismiss = { dialog = null },
        )
        DesktopMoreDialog.LANGUAGE -> DesktopChoiceSettingsDialog(
            title = desktopText("more.language"),
            summary = desktopText("more.languageDetail"),
            bodyTag = "language-dialog",
            optionTagPrefix = "language",
            options = listOf(desktopText("more.languageSystem") to "system") +
                DesktopLanguages.bundled.map { it.nativeName to it.tag },
            selected = state.language ?: "system",
            enabled = enabled,
            onPick = { tag -> controller.language(tag.takeUnless { it == "system" }); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class DesktopMoreDialog { THEME, INTERFACE_STYLE, LANGUAGE }


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
