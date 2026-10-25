package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview
import org.skepsun.kototoro.desktop.runtime.DesktopBookmark
import org.skepsun.kototoro.desktop.runtime.DesktopBookmarkGroup

/**
 * Android's bookmarks destination (`AppBookmarksRoute` / `AllBookmarksViewModel`): one section per work, its saved
 * positions below the work's title, and Android's selection actions REMOVE and SELECT ALL. Android enters the selection
 * by long-pressing a card; a mouse gets a checkbox on every row instead, the same deviation the library shelves took.
 *
 * Two deliberate deviations, both because the Windows page has no Android counterpart for the machinery involved:
 *  - Android's SAVE action (the page saver) has no Windows pipeline, so the toolbar keeps REMOVE only, and
 *  - Android draws poster cards whose thumbnail is the saved page; the shelf rows are text (chapter, position, novel
 *    excerpt) and only opening one touches the source, so a shelf of many works does not start a download per card.
 */
@Composable
internal fun DesktopBookmarksPage(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val groups = state.bookmarkGroups
    val selection = state.bookmarkShelfSelection
    val total = remember(groups) { groups.sumOf { it.bookmarks.size } }
    Column(Modifier.fillMaxSize().testTag("bookmarks-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (selection.isEmpty()) desktopText("reader.bookmarksCount", total)
            else desktopText("details.bookmarksSelectedCount", selection.size),
                modifier = Modifier.weight(1f).testTag("bookmarks-count"),
                style = MaterialTheme.typography.titleMedium, color = Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            TextButton({ controller.selectAllBookmarkShelf(true) },
                enabled = enabled && groups.isNotEmpty() && selection.size < total,
                modifier = Modifier.testTag("bookmarks-select-all")) { Text(desktopText("details.selectAll")) }
            TextButton({ controller.selectAllBookmarkShelf(false) }, enabled = enabled && selection.isNotEmpty(),
                modifier = Modifier.testTag("bookmarks-select-none")) { Text(desktopText("downloads.selectNone")) }
            OutlinedButton(controller::removeSelectedBookmarkShelf, enabled = enabled && selection.isNotEmpty(),
                modifier = Modifier.testTag("bookmarks-select-delete")) {
                Text(desktopText("details.bookmarksDeleteSelected", selection.size))
            }
        }
        if (groups.isEmpty()) {
            // Android's empty page: the same two lines, the second saying how a bookmark is made.
            Column(Modifier.fillMaxSize().testTag("bookmarks-empty"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(AndroidStrings["no_bookmarks_yet"], style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(AndroidStrings["no_bookmarks_summary"], color = Muted, fontSize = 13.sp)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("bookmarks-list"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(groups, key = { group -> group.content.id }) { group ->
                BookmarkGroupSection(controller, group, enabled, selection)
            }
        }
    }
}

@Composable
private fun BookmarkGroupSection(controller: DesktopController, group: DesktopBookmarkGroup, enabled: Boolean,
    selection: Set<Long>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Android's ListHeader: the work's title, and a click on it opens the work's details.
        Row(Modifier.fillMaxWidth().testTag("bookmark-group:${group.content.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(group.content.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(desktopText("reader.bookmarksCount", group.bookmarks.size), color = Muted, fontSize = 12.sp)
            TextButton({ if (enabled) controller.details(group.content) }, enabled = enabled,
                modifier = Modifier.testTag("bookmark-group-more:${group.content.id}")) {
                Text(AndroidStrings["more"])
            }
        }
        group.bookmarks.forEach { bookmark ->
            val checked = bookmark.pageId in selection
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().testTag("bookmark-row:${bookmark.pageId}")) {
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled && !checked) {
                    controller.openShelfBookmark(group, bookmark)
                }.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = checked, onCheckedChange = { controller.selectBookmarkShelf(bookmark.pageId) },
                        enabled = enabled, modifier = Modifier.testTag("bookmark-check:${bookmark.pageId}"))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(bookmark.chapterTitle.ifBlank { desktopText("reader.chapterMissing") },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        val excerpt = remember(bookmark.pageId) { parseNovelBookmarkPreview(bookmark.preview) }
                        if (excerpt.isNotBlank()) {
                            Text(excerpt, color = Muted, fontSize = 12.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(shelfLabel(bookmark), color = Accent, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Android stores a chapter bookmark under the chapter id, without a page of its own. */
private fun shelfLabel(bookmark: DesktopBookmark): String = if (bookmark.pageId == bookmark.chapterId) {
    desktopText("details.chapterBookmarkLabel")
} else {
    desktopText("common.pageOf", bookmark.page + 1, (bookmark.percent * 100).toInt())
}
