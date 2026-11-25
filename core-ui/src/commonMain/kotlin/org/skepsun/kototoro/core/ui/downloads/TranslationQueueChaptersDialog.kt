package org.skepsun.kototoro.core.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class QueuedTranslationChapter(val id: Long, val title: String)

@Composable
fun TranslationQueueChaptersDialog(
    title: String,
    chapters: List<QueuedTranslationChapter>,
    emptyLabel: String,
    closeLabel: String,
    moveUpLabel: String,
    moveDownLabel: String,
    cancelLabel: String,
    onMove: (Long, Int) -> Unit,
    onCancel: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (chapters.isEmpty()) {
                Text(emptyLabel)
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                        Column {
                            Text(chapter.title)
                            FlowRow {
                                TextButton(onClick = { onMove(chapter.id, -1) }, enabled = index > 0) {
                                    Text(moveUpLabel)
                                }
                                TextButton(onClick = { onMove(chapter.id, 1) }, enabled = index < chapters.lastIndex) {
                                    Text(moveDownLabel)
                                }
                                TextButton(onClick = { onCancel(chapter.id) }) { Text(cancelLabel) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(closeLabel) } },
    )
}
