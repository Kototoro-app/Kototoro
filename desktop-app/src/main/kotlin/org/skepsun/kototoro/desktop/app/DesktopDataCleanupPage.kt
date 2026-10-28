package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.DataCleanupContent
import org.skepsun.kototoro.core.ui.settings.DataCleanupRow
import org.skepsun.kototoro.core.ui.settings.DataCleanupSection

/**
 * Android's "data removal" page, one back press below the storage hub, drawn by the shared [DataCleanupContent].
 *
 * Android splits it into local storage, caches, privacy and chapters; Windows only ever had a few of those surfaces,
 * and the ones it does have were scattered: the image sweep lives on the downloads page, the update history had no
 * entry at all until the feed menu grew one, and the reader's page index could not be dropped anywhere. The page
 * therefore lists exactly the operations this data root really performs, grouped the way Android groups them, and
 * shows the figure each one acts on.
 *
 * Covers, reader pages and saved chapters share one image directory here, unlike Android's separate cache dirs, so the
 * image row is Android's `clear_thumbs_cache` over that whole cache: the budget keeps every blob a saved chapter needs.
 */
@Composable
internal fun DesktopDataCleanupPage(controller: DesktopController, state: DesktopAppState) {
    val active = !state.busy
    var confirmFeed by remember { mutableStateOf(false) }
    var confirmReadChapters by remember { mutableStateOf(false) }
    // The queue is the live count of saved chapters; the page's own state carries none of it.
    val savedChapters by controller.downloads.state.collectAsState()
    val sections = listOf(
        DataCleanupSection(
            key = "cache",
            title = AndroidStrings["cache"],
            rows = listOf(
                DataCleanupRow(
                    key = "image-cache",
                    title = AndroidStrings["clear_thumbs_cache"],
                    summary = desktopCacheSize(state.imageCache.usedBytes),
                    enabled = active,
                    onClick = { controller.clearImageCache() },
                ),
                DataCleanupRow(
                    key = "page-index",
                    title = AndroidStrings["clear_pages_cache"],
                    summary = desktopText("cleanup.pageIndexRecords", state.readerIndexCount),
                    enabled = active,
                    onClick = { controller.clearReaderPageCache() },
                ),
            ),
        ),
        DataCleanupSection(
            key = "privacy",
            title = AndroidStrings["privacy"],
            rows = listOf(
                DataCleanupRow(
                    key = "feed",
                    title = AndroidStrings["clear_updates_feed"],
                    summary = desktopText("cleanup.items", state.feed.rows.size),
                    enabled = active,
                    onClick = { confirmFeed = true },
                ),
            ),
        ),
        // Android's last group: the chapters the reading history has already passed, and the switch that runs the same
        // sweep at every start. The row is enabled only while something is downloaded — Android greys it out with the
        // same rule (no saved chapters, nothing to delete).
        DataCleanupSection(
            key = "chapters",
            title = AndroidStrings["chapters"],
            rows = listOf(
                DataCleanupRow(
                    key = "read-chapters",
                    title = AndroidStrings["delete_read_chapters"],
                    summary = AndroidStrings["delete_read_chapters_summary"],
                    enabled = active && savedChapters.isNotEmpty(),
                    onClick = { confirmReadChapters = true },
                ),
                DataCleanupRow(
                    key = "read-chapters-auto",
                    title = AndroidStrings["delete_read_chapters_auto"],
                    summary = AndroidStrings["runs_on_app_start"],
                    enabled = active,
                    checked = state.chaptersClearAuto,
                    onCheckedChange = { controller.setChaptersClearAuto(it) },
                ),
            ),
        ),
    )
    // The remaining Android rows act on stores this platform does not have yet (local manga/novel/video caches,
    // HTTP cache, search history, cookies and browser data, the database itself); they stay out instead of
    // offering a button that would clear nothing.
    DataCleanupContent(
        sections = sections,
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
    )
    if (confirmFeed) FeedClearDialog(controller, active) { confirmFeed = false }
    if (confirmReadChapters) {
        AlertDialog(
            onDismissRequest = { confirmReadChapters = false },
            title = { Text(AndroidStrings["delete_read_chapters"]) },
            text = { Text(AndroidStrings["delete_read_chapters_prompt"]) },
            confirmButton = {
                TextButton({ confirmReadChapters = false; controller.cleanupReadChapters() },
                    enabled = active, modifier = Modifier.testTag("cleanup-read-chapters-confirm")) {
                    Text(desktopText("common.ok"))
                }
            },
            dismissButton = {
                TextButton({ confirmReadChapters = false }, modifier = Modifier.testTag("cleanup-read-chapters-cancel")) {
                    Text(desktopText("common.cancel"))
                }
            },
            modifier = Modifier.testTag("cleanup-read-chapters-dialog"),
        )
    }
}
