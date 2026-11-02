package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceListing

/**
 * Android's MIGRATE offers the installed sources of the same content type, minus the one the work already lives on:
 * a work can only move between sources that serve the same kind of content, and moving onto itself is no change.
 */
internal fun desktopMigrationSources(
    listings: List<SourceListing>,
    content: SourceContent,
): List<SourceListing> {
    val type = content.source.contentType.removePrefix("HENTAI_")
    return listings
        .filter { it.source.name != content.source.name && it.source.contentType.removePrefix("HENTAI_") == type }
        .distinctBy { it.source.name }
}

/**
 * A search result can replace the work only when it comes from the chosen target source, is a different work and
 * keeps the content type; Android's migration list applies the same filter before offering candidates.
 */
internal fun desktopMigrationCandidates(
    items: List<SourceContent>,
    targetSource: String,
    content: SourceContent,
): List<SourceContent> {
    val type = content.source.contentType.removePrefix("HENTAI_")
    return items.filter {
        it.source.name == targetSource && it.id != content.id && it.source.contentType.removePrefix("HENTAI_") == type
    }
}

/**
 * What the migrate dialog shows: the work being replaced, the sources it can move to, and the state of the search
 * that fills the candidate list. Kept in the app state because the search is an IO action of the controller.
 */
data class DesktopMigrationState(
    val contentId: Long,
    val title: String,
    val sources: List<SourceListing>,
    val sourceName: String?,
    val query: String,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val candidates: List<SourceContent> = emptyList(),
    /** Android's COPY mode: the new work joins the library and the original keeps its own data. */
    val keepOriginal: Boolean = false,
) {
    val sourceLabel: String get() = sources.firstOrNull { it.source.name == sourceName }?.displayName.orEmpty()

    /** A migration needs a target source plus the target details, which the search delivers as a candidate. */
    val canMigrate: Boolean get() = sourceName != null && !searching
}

internal const val MIGRATE_SOURCE_OPTION_PREFIX = "library-migrate-source"

/**
 * Android's change-source flow for one work: pick a target source, search the work's title there, choose the matching
 * work and migrate. The dialog only collects the choice — the controller fetches the target details and asks the
 * shared library to commit the migration, so the dialog never mutates anything itself.
 */
@Composable
internal fun DesktopMigrationDialog(
    state: DesktopMigrationState,
    enabled: Boolean,
    onPickSource: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onKeepOriginal: (Boolean) -> Unit,
    onMigrate: (SourceContent) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(state.candidates) { mutableStateOf<SourceContent?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(desktopText("library.migrate")) },
        text = {
            Column(Modifier.testTag("library-migrate-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(desktopText("library.migrateHint"), style = MaterialTheme.typography.bodyMedium)
                TextButton({ onPickSource() }, enabled = enabled && state.sources.isNotEmpty(),
                    modifier = Modifier.testTag("library-migrate-source")) {
                    Text(desktopText("library.migrateSource", state.sourceLabel))
                }
                DesktopSearchField(state.query, onQuery, desktopText("library.search"), enabled = enabled,
                    tag = "library-migrate-query")
                TextButton({ onSearch() }, enabled = enabled && state.sourceName != null && !state.searching,
                    modifier = Modifier.testTag("library-migrate-search")) {
                    Text(desktopText("browse.search"))
                }
                val results = state.candidates
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    when {
                        state.searching -> Text(desktopText("library.migrateSearching"),
                            color = MaterialTheme.colorScheme.outline)
                        results.isEmpty() && state.searched ->
                            Text(desktopText("library.migrateEmpty"), color = MaterialTheme.colorScheme.outline)
                        else -> results.forEach { candidate ->
                            val selected = candidate.id == chosen?.id
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .clickable(enabled) { chosen = candidate }
                                    .testTag("library-migrate-candidate:${candidate.id}"),
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    val authors = candidate.authors.take(2).joinToString(", ")
                                    if (authors.isNotEmpty()) Text(authors, fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.keepOriginal, { onKeepOriginal(it) }, enabled = enabled,
                        modifier = Modifier.testTag("library-migrate-keep"))
                    Text(desktopText("library.migrateKeep"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton({ chosen?.let(onMigrate) }, enabled = enabled && state.canMigrate && chosen != null,
                modifier = Modifier.testTag("library-migrate-confirm")) {
                Text(desktopText("library.migrate"))
            }
        },
        dismissButton = {
            TextButton({ onDismiss() }, modifier = Modifier.testTag("library-migrate-dismiss")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}
