package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceTag

/**
 * Android's tag filter group. Options inside the group are OR-ed, so a work matches while it carries
 * any selected tag; [tags] arrives pre-counted and sorted so the list stays stable between recompositions.
 */
@Composable
internal fun DesktopTagPickerDialog(tags: List<Pair<SourceTag, Int>>, selected: Set<SourceTag>,
    onToggle: (SourceTag) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val text = query.trim()
    val visible = remember(tags, text) {
        if (text.isEmpty()) tags else tags.filter { it.first.title.contains(text, ignoreCase = true) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(desktopText("library.tags")) },
        modifier = Modifier.width(520.dp).testTag("library-tags-dialog"), text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopSearchField(query, { query = it }, desktopText("library.tagSearch"), tag = "library-tag-search")
                when {
                    tags.isEmpty() -> Text(desktopText("library.tagsEmpty"), color = Muted)
                    visible.isEmpty() -> Text(desktopText("library.tagsNoMatch"), color = Muted)
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(visible, key = { "${it.first.source.name}|${it.first.key}" }) { (tag, count) ->
                            TagRow(tag, count, tag in selected) { onToggle(tag) }
                        }
                    }
                }
            }
        }, confirmButton = {
            Button(onDismiss, modifier = Modifier.testTag("library-tag-done")) { Text(desktopText("common.close")) }
        }, dismissButton = {
            TextButton(onClear, enabled = selected.isNotEmpty(),
                modifier = Modifier.testTag("library-tag-clear")) { Text(desktopText("common.clear")) }
        })
}

@Composable
private fun TagRow(tag: SourceTag, count: Int, selected: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("library-tag:${tag.key}").clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null)
        Text(tag.title, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
        Text(count.toString(), color = Muted, fontSize = 12.sp)
    }
}
