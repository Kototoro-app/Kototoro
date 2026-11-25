package org.skepsun.kototoro.details.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.compose.DetailsSectionDivider
import org.skepsun.kototoro.details.ui.model.ChapterListItem

/**
 * The novel module of the details page: where the serial is up to, as one tappable line that opens the chapters
 * panel. The panel owns the list (search, order, selection, download state); this page only says whether there is
 * something new. [chapters] are in display order, so the latest one is the first when [isReversed].
 */
@Composable
internal fun NovelCatalogSummary(
    chapters: List<ChapterListItem>,
    totalCount: Int,
    isReversed: Boolean,
    horizontalPadding: Dp,
    onShowAll: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val latest = if (isReversed) chapters.firstOrNull() else chapters.lastOrNull()
    val latestTitle = remember(latest) { latest?.getTitle(resources) }
    val latestDate = remember(latest) { latest?.uploadDate?.toString() }
    DetailsSectionDivider(horizontalPadding = horizontalPadding, modifier = Modifier.padding(top = 12.dp))
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp)
            .tvFocusable(shape = shape, addFocusTarget = false)
            .clickable(onClick = onShowAll)
            .padding(vertical = 12.dp)
            .heightIn(min = 40.dp)
            .testTag("novel-catalog"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chapters),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (totalCount > 0) {
                    Text(
                        text = totalCount.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!latestTitle.isNullOrBlank()) {
                val updated = stringResource(R.string.details_catalog_updated_to, latestTitle)
                Text(
                    text = if (latestDate.isNullOrBlank()) updated else "$updated · $latestDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).testTag("novel-catalog-latest"),
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.show_all),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
