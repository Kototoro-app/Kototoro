package org.skepsun.kototoro.search.ui.compose

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

@Composable
internal fun rememberPreviewCoverRequest(content: Content): ImageRequest {
    val context = LocalContext.current
    return remember(content.id, content.largeCoverUrl, content.coverUrl, content.source) {
        ImageRequest.Builder(context)
            .data(content.largeCoverUrl ?: content.coverUrl)
            .mangaSourceExtra(content.source)
            .build()
    }
}

internal fun Content.previewDescription(): String {
    return HtmlCompat.fromHtml(description.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
}

internal fun Content.previewAuthors(): String = authors.take(2).joinToString(" · ")

@Composable
internal fun SearchPreviewChapterRow(
    chapter: ContentChapter,
    onClick: () -> Unit = {},
) {
    val title = chapter.title?.takeIf(String::isNotBlank)
        ?: chapter.numberString()?.let { "#$it" }
        ?: stringResource(R.string.unnamed_chapter)
    val metadata = remember(chapter.number, chapter.scanlator, chapter.branch, chapter.uploadDate) {
        buildList {
            chapter.numberString()?.let { add("#$it") }
            (chapter.scanlator?.takeIf(String::isNotBlank) ?: chapter.branch?.takeIf(String::isNotBlank))?.let(::add)
            if (chapter.uploadDate > 0L) {
                add(
                    DateUtils.getRelativeTimeSpanString(
                        chapter.uploadDate,
                        System.currentTimeMillis(),
                        DateUtils.DAY_IN_MILLIS,
                    ).toString(),
                )
            }
        }.joinToString(" · ")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = stringResource(R.string.read),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.read),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

internal data class PreviewChapterGroups(
    val earliest: List<ContentChapter>,
    val latest: List<ContentChapter>,
    val firstChapter: ContentChapter?,
    val isSingleGroup: Boolean,
)

internal fun Content.resolvePreviewChapters(): PreviewChapterGroups {
    val allChapters = chapters.orEmpty()
    if (allChapters.isEmpty()) {
        return PreviewChapterGroups(emptyList(), emptyList(), null, true)
    }

    val primaryBranch = allChapters.firstOrNull()?.branch
    val branchChapters = if (primaryBranch != null) {
        val filtered = allChapters.filter { it.branch == primaryBranch }
        if (filtered.isNotEmpty()) filtered else allChapters
    } else {
        allChapters
    }

    if (branchChapters.size <= 5) {
        return PreviewChapterGroups(
            earliest = branchChapters,
            latest = emptyList(),
            firstChapter = branchChapters.firstOrNull(),
            isSingleGroup = true,
        )
    }

    val first = branchChapters.first()
    val last = branchChapters.last()

    val isAscending = when {
        first.number > 0f && last.number > 0f && first.number != last.number -> {
            first.number < last.number
        }
        first.uploadDate > 0L && last.uploadDate > 0L && first.uploadDate != last.uploadDate -> {
            first.uploadDate < last.uploadDate
        }
        else -> true
    }

    val (earliestList, latestList) = if (isAscending) {
        val earliest = branchChapters.take(3)
        val latest = branchChapters.takeLast(3).reversed()
        earliest to latest
    } else {
        val latest = branchChapters.take(3)
        val earliest = branchChapters.takeLast(3).reversed()
        earliest to latest
    }

    return PreviewChapterGroups(
        earliest = earliestList,
        latest = latestList,
        firstChapter = earliestList.firstOrNull(),
        isSingleGroup = false,
    )
}

