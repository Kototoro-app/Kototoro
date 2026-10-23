package org.skepsun.kototoro.desktop.app

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
private fun DesktopSourceFilter.acceptsFeedSource(sourceName: String, contentType: String?, ecosystems: Map<String, org.skepsun.kototoro.core.source.SourceEcosystem>) =
    accepts(ecosystems[sourceName], contentType)

@Composable
internal fun DesktopFeedPanel(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    val filter = state.feedFilter
    // Android keeps two adult targets here: FEED hides the timeline, UPDATES its tracked-with-new-chapters carousel.
    val hideNsfwFeed = state.sourceSettings.hidesNsfw(DesktopNsfwTarget.FEED)
    val hideNsfwUpdates = state.sourceSettings.hidesNsfw(DesktopNsfwTarget.UPDATES)
    // Android derives the visible rows in memory from the snapshot (FeedDeriver), filters included.
    val derived = remember(state.feed, filter, hideNsfwFeed) {
        FeedDeriver.derive(
            FeedDeriver.Input(
                snapshot = state.feed,
                sourceTags = filter.tags,
                groupTab = filter.tab,
                excludedNsfw = hideNsfwFeed,
            ),
        )
    }
    val rows = derived.visibleRows
    val updates = remember(state.feed, state.feedContents, filter, ecosystems, hideNsfwUpdates) {
        state.feed.updateRowsByOwnerId.values
            .filter { !hideNsfwUpdates || !it.isNsfw }
            .filter { update ->
                val content = state.feedContents[update.displayMangaId ?: update.mangaId]
                filter.acceptsFeedSource(update.sourceName, content?.source?.contentType, ecosystems)
            }
            .sortedWith(compareByDescending<FeedUpdateRow> { it.isPinned }.thenByDescending { it.lastChapterDate ?: it.lastCheckTime })
    }
    Column(Modifier.fillMaxSize().testTag("feed-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBarTitleBlock(desktopText("nav.subscriptions"))
            Spacer(Modifier.weight(1f))
            DesktopSourceFilterControls(filter, state.sources, controller::feedFilter, enabled, "feed")
            Button({ controller.checkUpdates() }, enabled = enabled, modifier = Modifier.testTag("feed-check")) {
                Text(desktopText("feed.checkUpdates"))
            }
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
            if (updates.isNotEmpty()) item(key = "updated") {
                UpdatedContentCarousel(
                    items = updates,
                    key = { it.ownerId },
                    title = { it.title },
                    newChapters = { it.newChapters },
                    newChaptersLabel = { desktopText("common.newChapters", it) },
                    headerTitle = desktopText("feed.updatesTitle"),
                    moreLabel = desktopText("common.all"),
                    onItemClick = { controller.openTracked(it.displayMangaId ?: it.mangaId) },
                    onMoreClick = null,
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
private fun FeedRow(controller: DesktopController, state: DesktopAppState, row: FeedCardRow, label: String?, enabled: Boolean) {
    val content: SourceContent? = row.displayMangaId?.let(state.feedContents::get)
    val open = { row.displayMangaId?.let { if (enabled) controller.openTracked(it) } }
    FeedTimelineCard(
        title = row.overrideTitle ?: row.title,
        chapterText = desktopText("common.newChapters", row.chapters.size),
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
