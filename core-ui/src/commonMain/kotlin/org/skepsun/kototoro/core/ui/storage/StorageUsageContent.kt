package org.skepsun.kototoro.core.ui.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One category of the storage breakdown: Android lists seventeen of them, the desktop five (its data root holds no
 * per-media caches), so the row is keyed by an [id] the host chooses rather than by a shared enum. Both sides already
 * have their own label source and size formatter, so the row takes the words it prints and keeps only the layout —
 * colour dot, label, size, share.
 */
data class StorageUsageItem(
    val id: String,
    val label: String,
    val sizeLabel: String,
    val shareLabel: String,
    val color: Color,
)

/**
 * Android's legend row, which the desktop's settings group draws too: a colour dot, the category, its size and its
 * share of the application's own data. Android's donut highlights the slice the row selects, so the row is tappable
 * there; the desktop passes no [onSelect] and gets the same row without a highlight.
 */
@Composable
fun StorageUsageRow(
    item: StorageUsageItem,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onSelect: (() -> Unit)? = null,
    tagPrefix: String = "storage-usage",
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(10.dp).background(item.color, CircleShape))
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.sizeLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = item.shareLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 44.dp),
                textAlign = TextAlign.End,
            )
        }
    }
    val tag = Modifier.testTag("$tagPrefix:${item.id}")
    if (onSelect == null) {
        Box(modifier.then(tag)) { content() }
    } else {
        Surface(
            onClick = onSelect,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            color = if (selected) item.color.copy(alpha = 0.14f) else Color.Transparent,
            modifier = modifier.fillMaxWidth().then(tag),
        ) {
            content()
        }
    }
}

/** One column of the totals: the figure, then what it counts. Android shows the application's own data and the free
 * space; the desktop adds the volume's size, so the row takes as many columns as the host has answers for. */
data class StorageUsageTotal(
    val label: String,
    val valueLabel: String,
)

/**
 * Android's companion row under the chart: the application's own usage on the left, what is left on the volume on the
 * right, each as a small caption over a semibold figure.
 */
@Composable
fun StorageUsageTotals(
    totals: List<StorageUsageTotal>,
    modifier: Modifier = Modifier,
    tag: String? = "storage-usage-totals",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        totals.forEachIndexed { index, total ->
            Column(
                horizontalAlignment = if (index == 0) Alignment.Start else Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = total.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = total.valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
