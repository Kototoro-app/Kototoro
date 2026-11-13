package org.skepsun.kototoro.core.ui.chapters

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/**
 * Strips redundant `"line - "` prefix from video episode titles when the line picker already names the line,
 * mirroring iOS `SourceDetailsView.episodeTitle`.
 */
fun stripEpisodeLinePrefix(title: String, line: String?): String {
    if (line.isNullOrBlank()) return title
    val prefix = "$line - "
    return if (title.startsWith(prefix)) title.removePrefix(prefix) else title
}

/**
 * Uses compact grid chips for short episode labels (`01`, `第1集`, etc.) and falls back to full-width rows
 * once any of the first 50 episodes exceeds 7 characters, matching iOS `hasLongTitles`.
 */
fun hasLongEpisodeTitles(titles: List<String>): Boolean =
    titles.take(50).any { it.length > 7 }

/**
 * Picks up to [limit] episodes anchored around [currentId] when present so the user sees the current episode
 * alongside upcoming ones without opening the full directory, matching iOS `DetailsChapterPreview.items`.
 */
fun <T> selectEpisodePreviewWindow(
    episodes: List<T>,
    currentId: Long?,
    idSelector: (T) -> Long,
    limit: Int = 8,
): List<T> {
    if (limit <= 0 || episodes.isEmpty()) return emptyList()
    if (episodes.size <= limit) return episodes
    val currentIndex = currentId?.let { target -> episodes.indexOfFirst { idSelector(it) == target } } ?: -1
    if (currentIndex < 0) return episodes.take(limit)
    val maxStart = (episodes.size - limit).coerceAtLeast(0)
    val start = (currentIndex - 1).coerceIn(0, maxStart)
    val end = (start + limit).coerceAtMost(episodes.size)
    return episodes.subList(start, end)
}

@Immutable
data class VideoEpisodePreviewItem(
    val id: Long,
    val title: String,
    val subtitle: String? = null,
    val isCurrent: Boolean = false,
    val isWatched: Boolean = false,
)

@Immutable
data class VideoLineOption(
    val branch: String?,
    val label: String,
    val isSelected: Boolean,
)

/**
 * Shared inline episode preview section for video details: line chips, sort toggle, "All N" action, and
 * adaptive compact grid (or clean list when episode titles are long).
 */
@Composable
fun VideoEpisodePreviewSection(
    episodes: List<VideoEpisodePreviewItem>,
    totalCount: Int,
    modifier: Modifier = Modifier,
    lines: List<VideoLineOption> = emptyList(),
    isReversed: Boolean = false,
    onSelectLine: ((String?) -> Unit)? = null,
    onToggleReverse: (() -> Unit)? = null,
    onShowAllClick: (() -> Unit)? = null,
    onEpisodeClick: (VideoEpisodePreviewItem) -> Unit,
    previewLimit: Int = 8,
) {
    val currentId = episodes.firstOrNull { it.isCurrent }?.id
    val displayTitles = episodes.map { it.title }
    val longTitles = hasLongEpisodeTitles(displayTitles)
    val preview = selectEpisodePreviewWindow(
        episodes = episodes,
        currentId = currentId,
        idSelector = { it.id },
        limit = previewLimit,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("video-episode-preview"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = uiString("details_episodes"),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onToggleReverse != null && totalCount > 1) {
                IconButton(
                    onClick = onToggleReverse,
                    modifier = Modifier
                        .size(36.dp)
                        .tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false)
                        .testTag("video-episode-sort"),
                ) {
                    Icon(
                        imageVector = ReaderPanelIcons.SortDesc,
                        contentDescription = uiString("reverse_order"),
                        tint = if (isReversed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onShowAllClick != null && totalCount > 0) {
                TextButton(
                    onClick = onShowAllClick,
                    modifier = Modifier
                        .tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false)
                        .testTag("video-episode-show-all"),
                ) {
                    Text(
                        text = uiString("details_all_episodes", totalCount),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.size(2.dp))
                    Icon(
                        imageVector = ReaderPanelIcons.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        if (lines.size > 1 && onSelectLine != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lines.forEach { line ->
                    FilterChip(
                        selected = line.isSelected,
                        onClick = { onSelectLine(line.branch) },
                        label = { Text(line.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier
                            .tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false)
                            .testTag("video-line:${line.label}"),
                    )
                }
            }
        }

        if (preview.isEmpty()) {
            Text(
                text = uiString("details_no_episodes"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else if (!longTitles) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val hasLongerPreviewTitles = preview.any { it.title.length >= 4 } || preview.any { it.isCurrent }
                val columns = when {
                    maxWidth < 360.dp -> 3
                    maxWidth < 460.dp -> if (hasLongerPreviewTitles) 3 else 4
                    maxWidth < 640.dp -> (maxWidth / 92.dp).toInt().coerceIn(4, 6)
                    else -> (maxWidth / 84.dp).toInt().coerceIn(6, 8)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    preview.chunked(columns).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { episode ->
                                EpisodeGridChip(
                                    episode = episode,
                                    onClick = { onEpisodeClick(episode) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                preview.forEachIndexed { index, episode ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        )
                    }
                    EpisodePreviewListRow(
                        episode = episode,
                        onClick = { onEpisodeClick(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeGridChip(
    episode: VideoEpisodePreviewItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val containerColor = when {
        episode.isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        episode.isWatched -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f)
    }
    val borderColor = when {
        episode.isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        episode.isWatched -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }
    val contentColor = when {
        episode.isCurrent -> MaterialTheme.colorScheme.primary
        episode.isWatched -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .tvFocusable(shape = shape, addFocusTarget = false)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .testTag("video-episode-chip:${episode.id}"),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (episode.isCurrent) {
                Icon(
                    imageVector = ReaderPanelIcons.Play,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(13.dp),
                )
            }
            Text(
                text = episode.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (episode.isCurrent) FontWeight.Bold else FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EpisodePreviewListRow(
    episode: VideoEpisodePreviewItem,
    onClick: () -> Unit,
) {
    val contentColor = when {
        episode.isCurrent -> MaterialTheme.colorScheme.primary
        episode.isWatched -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (episode.isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .tvFocusable(shape = RoundedCornerShape(10.dp), addFocusTarget = false)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .testTag("video-episode-row:${episode.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .alpha(if (episode.isWatched && !episode.isCurrent) 0.70f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        if (episode.isCurrent) {
            Icon(
                imageVector = ReaderPanelIcons.Play,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (episode.isCurrent) FontWeight.Bold else FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!episode.subtitle.isNullOrBlank()) {
                Text(
                    text = episode.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
}
