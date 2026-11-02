package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The heading of one collection section: the work's title, an optional count line and one host-drawn trailing control.
 *
 * Android's top-level bookmarks grid labels each work with the row it draws above the cards, and the Windows bookmark
 * shelf draws the same heading above its text rows. What differs is only what each host puts at the end — Android the
 * chevron that opens the details, Windows the bookmark count and its "more" button — so the trailing control is a slot
 * and the whole row is clickable only when the host passes [onClick].
 */
@Composable
fun ContentCollectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    metaLabel: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 16.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (metaLabel != null) {
            Text(
                text = metaLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}
