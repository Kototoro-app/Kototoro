package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString

/** One of the reader's sources where a community work can be looked for. */
data class CommunitySourceChoice(
    val key: String,
    val name: String,
    /** How many of the work's readers named this source, or this site under another source; null when none did. */
    val readers: Int?,
    /** The readers named the same site under another source id, not this one. */
    val sameSite: Boolean = false,
)

/**
 * The "other sources" of a community work: the reader's sources its readers named, then their other ones, each to be
 * searched for it. [onSearchAll] falls back to searching every source for the title.
 */
@Composable
fun CommunitySourcePicker(
    title: String,
    choices: List<CommunitySourceChoice>?,
    onPick: (CommunitySourceChoice) -> Unit,
    onSearchAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().testTag("community-source-picker")) {
                Text(
                    uiString("community_other_sources"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (choices == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(choices, key = { it.key }) { choice ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clickable { onPick(choice) }
                                    .padding(vertical = 10.dp).testTag("community-source:${choice.key}"),
                            ) {
                                Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                                val detail = listOfNotNull(
                                    choice.readers?.let { uiString("community_source_readers", it) },
                                    uiString("community_source_same_site").takeIf { choice.sameSite },
                                ).joinToString(" · ")
                                if (detail.isNotEmpty()) {
                                    Text(detail, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSearchAll, modifier = Modifier.testTag("community-source-search-all")) {
                Text(uiString("search"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString("cancel")) } },
    )
}
