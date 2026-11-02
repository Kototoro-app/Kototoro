package org.skepsun.kototoro.core.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One source as a list row (Android's `KototoroSourceCard` list form): the icon in a 48dp tile, the bold name and a
 * one-line summary, on the selection container while selected. Icon loading, availability badges and what a click
 * means belong to the host; a host that wires its own clickable through [modifier] leaves [onClick] null.
 */
@Composable
fun SourceListRow(
    title: String,
    summary: String?,
    icon: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    expressive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    badge: @Composable BoxScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val listShape = RoundedCornerShape(if (expressive) 22.dp else 0.dp)
    val iconShape = RoundedCornerShape(if (expressive) 14.dp else 8.dp)
    val containerColor = when {
        selected -> colors.secondaryContainer
        expressive -> colors.surfaceContainerLow
        else -> colors.surface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (expressive) 8.dp else 0.dp, vertical = if (expressive) 3.dp else 0.dp)
            .clip(listShape)
            .background(containerColor, listShape)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = if (expressive) 10.dp else 12.dp, horizontal = if (expressive) 14.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp).background(colors.surfaceContainerHigh, iconShape),
            contentAlignment = Alignment.Center,
        ) {
            icon(Modifier.size(28.dp))
            badge()
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
