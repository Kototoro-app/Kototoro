package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.feed.FeedContinueAction
import org.skepsun.kototoro.core.ui.feed.FeedTimelineCard
import org.skepsun.kototoro.core.ui.feed.FeedUpdateDetails
import org.skepsun.kototoro.core.ui.feed.UpdatedContentCarousel
import org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgePill
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeText
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeTone
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.tracker.domain.feed.FeedCardRow
import org.skepsun.kototoro.tracker.domain.feed.FeedDeriver
import org.skepsun.kototoro.tracker.domain.feed.FeedUpdateRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Android's date buckets for the feed timeline ("just now", "today", "yesterday", "n days ago", then the date). */
internal fun feedDateLabel(createdAt: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val instant = Instant.ofEpochMilli(createdAt)
    val date = instant.atZone(zone).toLocalDate()
    val days = date.until(LocalDate.ofInstant(now, zone), ChronoUnit.DAYS)
    return when {
        days < 0 -> null
        days == 0L -> if (instant.until(now, ChronoUnit.MINUTES) < 3) desktopText("feed.timeNow") else desktopText("feed.timeToday")
        days == 1L -> desktopText("feed.timeYesterday")
        days < 6 -> desktopText("feed.timeDaysAgo", days)
        else -> date.format(DateTimeFormatter.ofPattern("MMMd", DesktopStrings.locale))
    }
}

/** A row passes the page's source-type and content-type filters through its recorded source. */
internal fun DesktopSourceFilter.acceptsFeedSource(
    sourceName: String,
    contentType: String?,
    ecosystems: Map<String, org.skepsun.kototoro.core.source.SourceEcosystem>,
) = accepts(ecosystems[sourceName], contentType)

@Composable
internal fun DesktopFeedPanel(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    val filter = state.feedFilter
    // Android keeps two adult targets here: FEED hides the timeline, UPDATES its tracked-with-new-chapters carousel.
    val hideNsfwFeed = state.sourceSettings.hidesNsfw(DesktopNsfwTarget.FEED)
    val hideNsfwUpdates = state.sourceSettings.hidesNsfw(DesktopNsfwTarget.UPDATES)
    // Android's FeedViewModel and UpdatesViewModel build one GlobalTagBlacklist from the same stored selection.
    val blacklist = remember(state.sourceSettings.tagBlacklist) { state.sourceSettings.tagBlacklistRules() }
    // Android derives the visible rows in memory from the snapshot (FeedDeriver), filters included.
    val derived = remember(state.feed, filter, hideNsfwFeed, blacklist) {
        FeedDeriver.derive(
            FeedDeriver.Input(
                snapshot = state.feed,
                sourceTags = filter.tags,
                groupTab = filter.tab,
                excludedNsfw = hideNsfwFeed,
                tagBlacklist = blacklist,
            ),
        )
    }
    val rows = derived.visibleRows
    val updates = remember(state.feed, state.feedContents, filter, ecosystems, hideNsfwUpdates, blacklist) {
        state.feed.updateRowsByOwnerId.values
            .filter { !hideNsfwUpdates || !it.isNsfw }
            .filter { update ->
                val content = state.feedContents[update.displayMangaId ?: update.mangaId]
                filter.acceptsFeedSource(update.sourceName, content?.source?.contentType, ecosystems) &&
                    content?.tags.orEmpty().none { blacklist.containsTagTitle(it.title) }
            }
            .sortedWith(compareByDescending<FeedUpdateRow> { it.isPinned }.thenByDescending { it.lastCheckTime })
    }
    Column(Modifier.fillMaxSize().testTag("feed-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBarTitleBlock(desktopText("nav.subscriptions"))
            Spacer(Modifier.weight(1f))
            DesktopSourceFilterControls(filter, state.sources, controller::feedFilter, enabled, "feed")
            Button({ controller.checkUpdates() }, enabled = enabled, modifier = Modifier.testTag("feed-check")) {
                Text(desktopText("feed.checkUpdates"))
            }
            // Android's feed menu (R.menu.opt_feed): its "update" item is the visible button above, the other two
            // have no room in the row and live behind the overflow.
            FeedOverflowMenu(controller, state, enabled)
        }
        if (state.trackedCategories == 0) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("feed-tracking-off")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(desktopText("feed.trackingOff"),
                        modifier = Modifier.weight(1f))
                    FilledTonalButton({ controller.enableTracking() }, enabled = enabled,
                        modifier = Modifier.testTag("feed-enable-tracking")) { Text(desktopText("feed.enableTracking")) }
                }
            }
        }
        val listState = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("feed-list"), state = listState) {
            if (updates.isNotEmpty() && state.feedDisplay.showUpdated) item(key = "updated") {
                UpdatedContentCarousel(
                    items = updates,
                    key = { it.ownerId },
                    title = { it.title },
                    newChapters = { it.newChapters },
                    newChaptersLabel = { desktopText("common.newChapters", it) },
                    headerTitle = desktopText("feed.updatesTitle"),
                    moreLabel = desktopText("common.all"),
                    onItemClick = { controller.openTracked(it.displayMangaId ?: it.mangaId) },
                    // Android's carousel header "all" opens the updates destination, so the carousel here leads to the
                    // same page instead of being a dead end.
                    onMoreClick = { controller.showUpdates() },
                    moreModifier = Modifier.testTag("feed-updated-more"),
                    screenPadding = 0.dp,
                    cardModifier = { Modifier.testTag("feed-updated:${it.ownerId}") },
                    cover = { update, _ ->
                        state.feedContents[update.displayMangaId ?: update.mangaId]?.let {
                            DesktopCover(it, controller.session.covers, Modifier.fillMaxSize(), large = true)
                        }
                    },
                    badges = { update, card ->
                        val metrics = remember(card.width) { contentCardBadgeMetricsFor(card.width) }
                        if (update.newChapters > 0) ContentCardBadgePill(ContentCardBadgeTone.COUNTER,
                            LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS, metrics,
                            Modifier.align(Alignment.TopEnd)) { colors ->
                            ContentCardBadgeText(update.newChapters.toString(), colors.content, metrics)
                        }
                    },
                )
            }
            if (rows.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Filters (including the 18+ timeline target) can hide every row while the snapshot still has some,
                    // so the hint has to stop telling the user to run a check they just ran.
                    if (derived.hasActiveFilters) {
                        Text(desktopText("feed.filteredTitle"), style = MaterialTheme.typography.titleMedium)
                        Text(desktopText("feed.filteredDetail"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(desktopText("feed.emptyTitle"), style = MaterialTheme.typography.titleMedium)
                        Text(desktopText("feed.emptyDetail"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            var previousLabel: String? = null
            val labelled = rows.map { row ->
                val label = feedDateLabel(row.createdAt)
                (row to label.takeIf { it != previousLabel }).also { previousLabel = label }
            }
            items(labelled, key = { it.first.logId }) { (row, label) ->
                FeedRow(controller, state, row, label, enabled)
            }
        }
    }
}

@Composable
private fun FeedOverflowMenu(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, enabled = enabled, modifier = Modifier.testTag("feed-menu")) {
            Icon(painterResource("icons/ic_more_vert.svg"), contentDescription = null)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                onClick = {
                    open = false
                    controller.setShowFeedUpdated(!state.feedDisplay.showUpdated)
                },
                enabled = enabled,
                modifier = Modifier.testTag("feed-menu-show-updated"),
                text = { Text(AndroidStrings["show_updated"]) },
                leadingIcon = {
                    if (state.feedDisplay.showUpdated) Icon(painterResource("icons/ic_check.svg"), null)
                    else Spacer(Modifier.size(24.dp))
                },
            )
            DropdownMenuItem(
                onClick = {
                    open = false
                    confirmClear = true
                },
                enabled = enabled,
                modifier = Modifier.testTag("feed-menu-clear"),
                text = { Text(AndroidStrings["clear_feed"]) },
            )
        }
    }
    if (confirmClear) FeedClearDialog(controller, enabled) { confirmClear = false }
}

/** Android's clear-feed confirmation: same title, prompt and checkbox, same "clear the counters too" default. */
@Composable
internal fun FeedClearDialog(controller: DesktopController, enabled: Boolean, onDismiss: () -> Unit) {
    var clearCounters by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource("icons/ic_more_vert.svg"), null) },
        title = { Text(AndroidStrings["clear_updates_feed"]) },
        text = {
            Column(Modifier.testTag("feed-clear-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AndroidStrings["text_clear_updates_feed_prompt"])
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                    .fillMaxWidth()
                    .testTag("feed-clear-counters")
                    .clickable { clearCounters = !clearCounters }) {
                    Checkbox(checked = clearCounters, onCheckedChange = { clearCounters = it })
                    Text(AndroidStrings["clear_new_chapters_counters"])
                }
            }
        },
        confirmButton = {
            TextButton({
                onDismiss()
                controller.clearFeed(clearCounters)
            }, enabled = enabled, modifier = Modifier.testTag("feed-clear-confirm")) {
                Text(desktopText("common.clear"))
            }
        },
        dismissButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("feed-clear-cancel")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}

@Composable
private fun FeedRow(controller: DesktopController, state: DesktopAppState, row: FeedCardRow, label: String?, enabled: Boolean) {
    val content: SourceContent? = row.displayMangaId?.let(state.feedContents::get)
    val open = { row.displayMangaId?.let { if (enabled) controller.openTracked(it, row.logIds) } }
    FeedTimelineCard(
        supportingContent = {
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(DesktopStrings.locale)
            fun date(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(format)
            val dates = row.updateChapters.map { it.publishedAt }.filter { it > 0 }
            val unknown = desktopText("feed.sourceTimeUnknown")
            val summary = if (dates.isEmpty()) unknown else {
                val from = date(dates.min())
                val until = date(dates.max())
                desktopText("feed.sourceTime", if (from == until) from else "$from — $until") +
                    if (dates.size < row.updateChapters.size) " · " + desktopText("feed.sourceTimePartial") else ""
            }
            FeedUpdateDetails(summary, row.updateChapters.map { chapter ->
                chapter.title to if (chapter.publishedAt > 0) desktopText("feed.sourceTime", date(chapter.publishedAt)) else unknown
            }, desktopText("feed.chapterDetails"), desktopText("common.close")) {
                controller.acknowledgeTracked(row.ownerId, row.logIds)
            }
        },
        title = row.overrideTitle ?: row.title,
        chapterText = (if (row.updateCount < 0) desktopText("feed.updatesAvailable")
            else desktopText("common.newChapters", row.updateCount)) + when {
            row.pendingCount < 0 -> ""
            row.pendingCount == 0 -> " · " + desktopText("feed.readCompleted")
            else -> " · " + desktopText("feed.pendingChapters", row.pendingCount)
        },
        isNew = row.unread,
        onClick = { open() },
        modifier = Modifier.testTag("feed-row:${row.logId}"),
        screenPadding = 0.dp,
        timelineLabel = label,
        continueAction = FeedContinueAction(painterResource("icons/ic_read.svg"), desktopText("feed.continueReading")) {
            row.displayMangaId?.let { if (enabled) controller.continueTracked(it) }
        },
        cover = { content?.let { DesktopCover(it, controller.session.covers, Modifier.matchParentSize()) } },
    )
}
