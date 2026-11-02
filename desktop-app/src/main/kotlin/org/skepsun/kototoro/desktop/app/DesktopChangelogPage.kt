package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.markdown.MarkdownText
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateRelease

/**
 * Android's changelog screen (`settings/about/changelog/ChangelogScreen`) for the Windows host: this channel's
 * installable releases, newest first, each with its version, date and Markdown notes.
 *
 * Android joins every release into one Markdown string and renders it with Markwon; here each release is a card of its
 * own so a long history stays readable, and the shared [MarkdownText] renders the same subset of Markdown.
 */
@Composable
internal fun DesktopChangelogPage(controller: DesktopController, state: DesktopAppState) {
    val update = state.update
    val active = !state.busy
    val releases = update.changelog
    Column(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            OutlinedButton({ controller.reloadChangelog() }, enabled = active && !update.changelogLoading,
                modifier = Modifier.testTag("changelog-reload")) { Text(desktopText("update.changelogReload")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AndroidStrings["changelog_summary"], style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.testTag("changelog-summary"))
            Text(desktopText("update.version", update.version.versionName.ifEmpty { "—" }),
                style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.testTag("changelog-version"))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            update.changelogError != null -> Text(update.changelogError, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("changelog-error"))

            // Null means the notes have not arrived yet: an empty list is a real answer, "not asked" is not.
            releases == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(desktopText("update.changelogLoading"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("changelog-loading"))
            }

            releases.isEmpty() -> Text(desktopText("update.changelogEmpty"),
                style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.testTag("changelog-empty"))

            else -> LazyColumn(Modifier.fillMaxWidth().testTag("changelog-list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(releases, key = { it.tag }) { release ->
                    ChangelogReleaseCard(release, isCurrent = release.version == update.version.version)
                }
            }
        }
    }
}

@Composable
private fun ChangelogReleaseCard(release: DesktopUpdateRelease, isCurrent: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag("changelog-release:${release.tag}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(release.version.text, style = MaterialTheme.typography.titleMedium)
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(desktopText("update.changelogCurrent"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                desktopReleaseDate(release.publishedAt)?.let {
                    Text(desktopText("update.published", it), fontSize = 12.sp, color = Muted)
                }
                release.packages.values.firstOrNull()?.let {
                    Text(desktopText("update.size", desktopCacheSize(it.sizeBytes)), fontSize = 12.sp, color = Muted)
                }
            }
            if (release.notes.isBlank()) {
                Text(desktopText("update.noNotes"), style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                MarkdownText(release.notes, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("changelog-notes:${release.tag}"))
            }
        }
    }
}
