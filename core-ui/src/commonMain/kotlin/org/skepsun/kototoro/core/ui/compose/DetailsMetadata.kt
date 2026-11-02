package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/**
 * One fact of the details info card (Android's `MetadataItem`): a 15dp icon that carries the [label] for screen
 * readers, then the value on one line. A muted value fades both; a clickable fact may end with [navigationIcon].
 */
@Composable
fun DetailsMetadataItem(
    label: String,
    value: String,
    icon: Painter?,
    modifier: Modifier = Modifier,
    valueMuted: Boolean = false,
    onClick: (() -> Unit)? = null,
    navigationIcon: Painter? = null,
) {
    val contentAlpha = if (valueMuted) 0.62f else 1f
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .tvFocusable(shape = RoundedCornerShape(8.dp), addFocusTarget = false)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            // Without an icon the label would be lost to a screen reader; the row says it instead.
            .then(if (icon == null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = label, tint = color, modifier = Modifier.size(15.dp)) }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        navigationIcon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
    }
}

/** One fact for [DetailsMetadataGrid]. */
class DetailsMetadataEntry(
    val label: String,
    val value: String,
    val icon: Painter?,
    val valueMuted: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val navigationIcon: Painter? = null,
    val modifier: Modifier = Modifier,
)

/** Android's two-column fact grid: facts in pairs, a lone last fact keeping half the width. */
@Composable
fun DetailsMetadataGrid(
    entries: List<DetailsMetadataEntry>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        entries.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { entry ->
                    DetailsMetadataItem(
                        label = entry.label,
                        value = entry.value,
                        icon = entry.icon,
                        modifier = entry.modifier.weight(1f),
                        valueMuted = entry.valueMuted,
                        onClick = entry.onClick,
                        navigationIcon = entry.navigationIcon,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
