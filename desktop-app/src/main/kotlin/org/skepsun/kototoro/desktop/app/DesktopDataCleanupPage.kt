package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/**
 * Android's "data removal" page, one back press below the storage hub.
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
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.backToStorage() }, modifier = Modifier.testTag("data-cleanup-back")) {
            Text(AndroidStrings["storage_and_network"])
        }
        SettingsSectionHeader(AndroidStrings["cache"])
        SettingsGroup {
            SettingsValueRow(
                title = AndroidStrings["clear_thumbs_cache"],
                summary = desktopCacheSize(state.imageCache.usedBytes),
                value = null,
                onClick = { controller.clearImageCache() },
                enabled = active,
                modifier = Modifier.testTag("cleanup-image-cache"),
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = AndroidStrings["clear_pages_cache"],
                summary = desktopText("cleanup.pageIndexRecords", state.readerIndexCount),
                value = null,
                onClick = { controller.clearReaderPageCache() },
                enabled = active,
                modifier = Modifier.testTag("cleanup-page-index"),
            )
        }
        SettingsSectionHeader(AndroidStrings["privacy"])
        SettingsGroup {
            SettingsValueRow(
                title = AndroidStrings["clear_updates_feed"],
                summary = desktopText("cleanup.items", state.feed.rows.size),
                value = null,
                onClick = { confirmFeed = true },
                enabled = active,
                modifier = Modifier.testTag("cleanup-feed"),
            )
        }
        // The remaining Android rows act on stores this platform does not have yet (local manga/novel/video caches,
        // HTTP cache, search history, cookies and browser data, the database itself); they stay out instead of
        // offering a button that would clear nothing. The chapter row is on the downloads page as batch removal.
    }
    if (confirmFeed) FeedClearDialog(controller, active) { confirmFeed = false }
}
