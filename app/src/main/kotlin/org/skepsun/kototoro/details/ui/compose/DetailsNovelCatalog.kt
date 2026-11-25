package org.skepsun.kototoro.details.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.skepsun.kototoro.core.ui.compose.DetailsSectionHeader
import org.skepsun.kototoro.details.ui.model.ChapterListItem

/** One line of [NovelCatalogSection]: a chapter and the word that says why it is listed (continue, latest). */
internal data class NovelCatalogRow(val item: ChapterListItem, val tag: NovelCatalogTag?)

internal enum class NovelCatalogTag { CONTINUE, LATEST }

/**
 * The few chapters a novel's details page lists before the full catalogue: where the reader is and the chapter after
 * it (or the first chapter before they start), then the latest one. [chapters] in reading order.
 */
internal fun novelCatalogRows(chapters: List<ChapterListItem>): List<NovelCatalogRow> {
    if (chapters.isEmpty()) return emptyList()
    val current = chapters.indexOfFirst { it.isCurrent }
    val rows = buildList {
        if (current >= 0) {
            add(NovelCatalogRow(chapters[current], NovelCatalogTag.CONTINUE))
            chapters.getOrNull(current + 1)?.let { add(NovelCatalogRow(it, null)) }
        } else {
            add(NovelCatalogRow(chapters.first(), null))
        }
    }
    val latest = chapters.last()
    return if (rows.any { it.item.chapter.id == latest.chapter.id }) rows else rows + NovelCatalogRow(latest, NovelCatalogTag.LATEST)
}

/** The novel module of the details page: a section, not a card, that opens a chapter or the whole catalogue. */
@Composable
internal fun NovelCatalogSection(
    chapters: List<ChapterListItem>,
    totalCount: Int,
    isReversed: Boolean,
    horizontalPadding: Dp,
    onOpenChapter: (Long) -> Unit,
    onShowAll: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val rows = remember(chapters, isReversed) {
        novelCatalogRows(if (isReversed) chapters.asReversed() else chapters)
    }
    if (rows.isEmpty()) return
    DetailsSectionDivider(horizontalPadding = horizontalPadding, modifier = Modifier.padding(top = 12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 12.dp)
            .testTag("novel-catalog"),
    ) {
        DetailsSectionHeader(title = stringResource(R.string.chapters), count = totalCount) {
            TextButton(
                onClick = onShowAll,
                modifier = Modifier.tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false),
            ) {
                Text(stringResource(R.string.show_all))
            }
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            val highlighted = row.tag == NovelCatalogTag.CONTINUE
            val shape = RoundedCornerShape(8.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .tvFocusable(shape = shape, addFocusTarget = false)
                    .clickable { onOpenChapter(row.item.chapter.id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.item.getTitle(resources),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                row.tag?.let { tag ->
                    Text(
                        text = stringResource(
                            when (tag) {
                                NovelCatalogTag.CONTINUE -> R.string._continue
                                NovelCatalogTag.LATEST -> R.string.newest
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
