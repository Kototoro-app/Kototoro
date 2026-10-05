package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** Platform hosts supply normalized text and dates; the bookmark card has one layout on all readers. */
@Composable
fun NovelBookmarkCardContent(
    preview: String,
    positionText: String,
    chapterName: String?,
    dateText: String,
    deleteDescription: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onOpen,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.width(4.dp).height(42.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .75f)))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = preview.ifBlank { positionText },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.testTag("novel-bookmark-delete"),
                ) {
                    Icon(imageVector = ReaderPanelIcons.Delete, contentDescription = deleteDescription)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = chapterName?.takeIf { it.isNotBlank() } ?: positionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dateText, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
