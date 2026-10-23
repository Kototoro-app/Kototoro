package org.skepsun.kototoro.core.ui.chapters

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** Resolved presentation only; chapter identity, resources and persistence stay in each host. */
@Immutable
data class ChapterCardData(
    val title: String,
    val description: String? = null,
    val isCurrent: Boolean = false,
    val isUnread: Boolean = true,
    val isBookmarked: Boolean = false,
    val isDownloaded: Boolean = false,
    val isNew: Boolean = false,
    /** A background translation of this chapter already finished with the current settings. */
    val isTranslated: Boolean = false,
    /** Pixels exist but were produced by other translation settings; the row offers a re-translate. */
    val isTranslationStale: Boolean = false,
)

/** Android's chapter row, including current/read, selection, bookmark and download presentation. */
@Composable
fun ChapterListCard(
    item: ChapterCardData,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
    enabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val titleColor = when {
        item.isCurrent -> MaterialTheme.colorScheme.primary
        item.isUnread -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alphaFactor = if (item.isUnread || item.isCurrent) 1f else .6f
    // The trailing actions are a separate focus stop: a D-pad cannot steer into a child that sits
    // inside the focused node's bounds, so the click target covers the text area only and the
    // buttons stay outside it. Touch is unchanged — the whole text row is still one big target.
    Row(
        modifier = modifier.fillMaxWidth()
            // Inset so a highlighted row reads as a card rather than an edge-to-edge band.
            .padding(horizontal = 8.dp)
            .then(when {
                isSelected -> Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), RoundedCornerShape(8.dp))
                // The small marker alone is hard to spot while scanning a long list.
                item.isCurrent -> Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), RoundedCornerShape(12.dp))
                else -> Modifier
            }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(focusModifier)
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isCurrent) {
                Icon(ReaderPanelIcons.CurrentChapter, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Column(Modifier.weight(1f).alpha(alphaFactor)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (item.isCurrent) FontWeight.Bold else null, color = titleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!item.description.isNullOrBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (item.isBookmarked) {
                Icon(ReaderPanelIcons.Bookmark, "Bookmarked", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).size(24.dp))
            }
            if (item.isDownloaded) {
                Icon(ReaderPanelIcons.Storage, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(24.dp))
            }
            if (item.isTranslated || item.isTranslationStale) {
                // A filled chip, so the "translated" mark cannot be mistaken for the row's translate button.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.isTranslationStale) {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ReaderPanelIcons.Translate,
                        contentDescription = if (item.isTranslationStale) {
                            "Translated with older settings"
                        } else {
                            "Translated"
                        },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        actions?.invoke(this)
    }
}

/** Android's square chapter card with its size-dependent typography and corner status markers. */
@Composable
fun ChapterGridCard(
    item: ChapterCardData,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
    enabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val titleColor = if (item.isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.fillMaxWidth().alpha(if (item.isUnread) 1f else .6f)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(focusModifier)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)),
        border = if (!item.isUnread && !isSelected) null else CardDefaults.outlinedCardBorder(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val titleStyle = when {
                maxWidth < 56.dp -> MaterialTheme.typography.labelSmall
                maxWidth < 72.dp -> MaterialTheme.typography.bodySmall
                maxWidth < 88.dp -> MaterialTheme.typography.bodyMedium
                else -> MaterialTheme.typography.titleMedium
            }
            Text(item.title, style = titleStyle, color = titleColor, maxLines = 1, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.isDownloaded) {
                    Icon(ReaderPanelIcons.SaveOk, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp))
                }
                if (item.isTranslated || item.isTranslationStale) {
                    Icon(ReaderPanelIcons.Translate, "Translated", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp))
                }
                if (item.isBookmarked) {
                    Icon(ReaderPanelIcons.Bookmark, "Bookmarked", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp))
                }
            }
            if (item.isNew) {
                Icon(ReaderPanelIcons.New, "New", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(8.dp))
            }
            if (item.isCurrent) {
                Icon(ReaderPanelIcons.CurrentChapter, "Current", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp))
            }
        }
    }
    if (actions != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
    }
    }
}
