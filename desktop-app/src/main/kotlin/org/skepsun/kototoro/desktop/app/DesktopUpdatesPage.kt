package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.page.PageSummaryAction
import org.skepsun.kototoro.core.ui.page.PageSummaryHeader
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateGroup
import org.skepsun.kototoro.desktop.runtime.desktopUpdateGroups
import org.skepsun.kototoro.desktop.runtime.desktopUpdatesSummary

/**
 * Android's updates destination (`NavItem.UPDATED` → `UpdatedTopLevelRouteContent`): the tracked works the last check
 * found new chapters for, one card per work, with the header card's count and its "mark all read" action.
 *
 * The rows come from the same feed snapshot the subscriptions page draws, so both pages agree the moment a check
 * finishes; [desktopUpdateGroups] does the folding and the ordering, and the feed's own source filter (the bar above
 * the list) and adult target `sources.nsfw.updates` apply here exactly as they do to the subscriptions carousel.
 *
 * Two deviations from the phone, both because the machinery does not exist on Windows: there is no selection mode (a
 * mouse gets a per-row "remove" instead of Android's long-press bar), and the row's "remove" is Android's own
 * `clearUpdates` write — pending counters drained, history and reading progress untouched.
 */
@Composable
internal fun DesktopUpdatesPage(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    val filter = state.feedFilter
    // Android hides these works behind the same adult target as the feed's carousel.
    val hideNsfw = state.sourceSettings.hidesNsfw(DesktopNsfwTarget.UPDATES)
    val blacklist = remember(state.sourceSettings.tagBlacklist) { state.sourceSettings.tagBlacklistRules() }
    val contents = state.feedContents
    val groups = remember(state.feed, contents, filter, ecosystems, hideNsfw, blacklist) {
        desktopUpdateGroups(
            rows = state.feed.updateRowsByOwnerId.values,
            hideNsfw = hideNsfw,
        ) { row ->
            val content = contents[row.displayMangaId ?: row.mangaId]
            filter.acceptsFeedSource(row.sourceName, content?.source?.contentType, ecosystems) &&
                content?.tags.orEmpty().none { blacklist.containsTagTitle(it.title) }
        }
    }
    val summary = remember(groups) { desktopUpdatesSummary(groups) }
    Column(Modifier.fillMaxSize().testTag("updates-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Android's UpdatesHeaderCard: the summary line and its two pills, on the shared page-summary card.
        val hasUpdates = groups.isNotEmpty()
        PageSummaryHeader(
            title = if (hasUpdates) AndroidStrings["updated_header_title"] else AndroidStrings["updated_header_up_to_date"],
            subtitle = if (hasUpdates) {
                AndroidStrings.format("updated_header_summary", summary.works, summary.newChapters)
            } else {
                AndroidStrings["updated_header_up_to_date_subtitle"]
            },
            icon = painterResource("icons/ic_updated.svg"),
            horizontalPadding = 0.dp,
            modifier = Modifier.testTag("updates-summary"),
        ) {
            if (hasUpdates) {
                // Check for updates is the icon-only pill Android shows beside the emphasised mark-all-read.
                PageSummaryAction(
                    text = null,
                    icon = painterResource("icons/ic_sync.svg"),
                    contentDescription = AndroidStrings["check_for_updates"],
                    enabled = enabled,
                    onClick = { controller.checkUpdates() },
                    modifier = Modifier.testTag("updates-check"),
                )
                PageSummaryAction(
                    text = AndroidStrings["updated_header_all_read"],
                    emphasized = true,
                    enabled = enabled,
                    onClick = { controller.markUpdatesRead(groups) },
                    modifier = Modifier.testTag("updates-mark-all-read"),
                )
            } else {
                PageSummaryAction(
                    text = AndroidStrings["check_for_updates"],
                    icon = painterResource("icons/ic_sync.svg"),
                    enabled = enabled,
                    onClick = { controller.checkUpdates() },
                    modifier = Modifier.testTag("updates-check"),
                )
            }
        }
        // Android keeps the source/ecosystem filters off the header card, so this row stays its own line.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopSourceFilterControls(filter, state.sources, controller::feedFilter, enabled, "updates")
        }
        if (state.trackedCategories == 0) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("updates-tracking-off")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(desktopText("feed.trackingOff"), modifier = Modifier.weight(1f))
                    FilledTonalButton({ controller.enableTracking() }, enabled = enabled,
                        modifier = Modifier.testTag("updates-enable-tracking")) {
                        Text(desktopText("feed.enableTracking"))
                    }
                }
            }
        }
        if (groups.isEmpty()) {
            // The card above already carries Android's "up to date" line, so the body is only the empty placeholder.
            Box(Modifier.weight(1f).fillMaxWidth().testTag("updates-empty"))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("updates-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.key }) { group ->
                    DesktopUpdateRow(group, contents[group.key], controller, enabled)
                }
            }
        }
    }
}

/** One work with new chapters: what it is, how many chapters wait and what opening it does. */
@Composable
private fun DesktopUpdateRow(
    group: DesktopUpdateGroup,
    content: org.skepsun.kototoro.core.source.SourceContent?,
    controller: DesktopController,
    enabled: Boolean,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("update-row:${group.key}")) {
        Row(
            modifier = Modifier.clickable(enabled = enabled) { controller.openTracked(group.key) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 52.dp, height = 72.dp)) {
                if (content != null) {
                    DesktopCover(content, controller.session.covers, Modifier.fillMaxSize())
                } else {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {}
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (group.isPinned) {
                        Icon(painterResource("icons/ic_pin.svg"), null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    group.sourceName + " · " + desktopText("common.newChapters", group.newChapters),
                    color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("update-chapters:${group.key}"),
                )
                Text(
                    group.lastChapterDate?.let { feedDateLabel(it) } ?: AndroidStrings["updated_long_ago"],
                    color = Muted, fontSize = 12.sp, maxLines = 1,
                )
            }
            TextButton({ controller.continueTracked(group.key) }, enabled = enabled,
                modifier = Modifier.testTag("update-continue:${group.key}")) {
                Text(desktopText("feed.continueReading"))
            }
            TextButton({ controller.markUpdatesRead(listOf(group)) }, enabled = enabled,
                modifier = Modifier.testTag("update-read:${group.key}")) {
                Text(AndroidStrings["remove"])
            }
        }
    }
}
