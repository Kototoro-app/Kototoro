package org.skepsun.kototoro.core.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Android's tablet preview, with image loading, localized values and actions supplied by each host. */
@Composable
fun TabletPreviewContent(
    data: TabletPreviewData,
    labels: TabletPreviewLabels,
    cover: @Composable (Modifier) -> Unit,
    icon: @Composable (TabletPreviewIcon) -> Painter,
    onClose: () -> Unit,
    onRead: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddToFavorites: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    hasLoadError: Boolean = false,
    onRetry: () -> Unit = {},
    controlsEnabled: Boolean = true,
    favouriteEnabled: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("tablet-preview-content")) {
        // Short windows scroll the header with the body; taller windows keep the artwork visible.
        val compact = maxHeight < 520.dp
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            TabletPreviewHeader(data, labels, compact, cover, icon, onClose, onOpenDetails, controlsEnabled)
            TabletPreviewBody(data, labels, icon, !compact, isLoading, hasLoadError, onRetry, onRead,
                onOpenDetails, onAddToFavorites, onOpenChapter, controlsEnabled, favouriteEnabled)
        }
    }
}

@Composable
private fun TabletPreviewHeader(
    data: TabletPreviewData,
    labels: TabletPreviewLabels,
    compact: Boolean,
    cover: @Composable (Modifier) -> Unit,
    icon: @Composable (TabletPreviewIcon) -> Painter,
    onClose: () -> Unit,
    onOpenDetails: () -> Unit,
    enabled: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(if (compact) 132.dp else 200.dp)
        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).testTag("tablet-preview-header")) {
        cover(Modifier.matchParentSize().blur(24.dp).testTag("tablet-preview-artwork"))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .25f), 1f to Color.Black.copy(alpha = .65f))))
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom) {
            cover(Modifier.size(if (compact) 70.dp else 112.dp, if (compact) 100.dp else 160.dp)
                .clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onOpenDetails))
            Column(Modifier.weight(1f).padding(end = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(data.title, style = if (compact) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = enabled, onClick = onOpenDetails))
                if (data.authors.isNotBlank()) Text(data.authors, style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(data.sourceTitle, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClose, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            .size(36.dp).background(Color.Black.copy(alpha = .3f), CircleShape).testTag("details-close")) {
            Icon(icon(TabletPreviewIcon.CLOSE), labels.close, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletPreviewBody(
    data: TabletPreviewData,
    labels: TabletPreviewLabels,
    icon: @Composable (TabletPreviewIcon) -> Painter,
    scrollable: Boolean,
    isLoading: Boolean,
    hasLoadError: Boolean,
    onRetry: () -> Unit,
    onRead: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddToFavorites: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    enabled: Boolean,
    favouriteEnabled: Boolean,
) {
    var descriptionExpanded by rememberSaveable(data.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (hasLoadError) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(labels.unavailable, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onRetry, enabled = enabled) { Text(labels.retry) }
        }
        if (data.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), maxLines = 1) {
            data.tags.take(6).forEach { TabletPreviewTag(it) }
            if (data.tags.size > 6) TabletPreviewTag("+${data.tags.size - 6}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            data.ratingText?.let { rating ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(icon(TabletPreviewIcon.RATING), null, tint = Color(0xFFFFB800), modifier = Modifier.size(16.dp))
                    Text(rating, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            data.chapterCountText?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            data.statusLabel?.let { TabletPreviewTag(it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(onRead, enabled = enabled, shape = CircleShape,
                modifier = Modifier.weight(1f).testTag("preview-read")) {
                Icon(icon(TabletPreviewIcon.PLAY), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(labels.read, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onOpenDetails, enabled = enabled, shape = CircleShape,
                modifier = Modifier.testTag("details-expand")) { Text(labels.details) }
            OutlinedIconButton(onAddToFavorites, enabled = enabled && favouriteEnabled,
                modifier = Modifier.testTag("preview-favourite")) {
                Icon(icon(TabletPreviewIcon.FAVOURITE), labels.addToFavorites, modifier = Modifier.size(18.dp))
            }
        }
        if (data.description.isNotBlank()) Text(data.description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { descriptionExpanded = !descriptionExpanded })
        data.chapterSections.forEach { section ->
            if (section.chapters.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    section.countText?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = enabled, onClick = onOpenDetails))
                    }
                }
                section.chapters.forEach { chapter ->
                    TabletPreviewChapterRow(chapter, labels.read, icon(TabletPreviewIcon.PLAY), enabled) {
                        onOpenChapter(chapter.id)
                    }
                }
            }
        }
    }
}

@Composable
fun TabletPreviewChapterRow(
    chapter: TabletPreviewChapter,
    readLabel: String,
    playIcon: Painter,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
        .testTag("preview-chapter:${chapter.id}"), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chapter.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chapter.metadata.isNotBlank()) Text(chapter.metadata, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(playIcon, readLabel, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    Text(readLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TabletPreviewTag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
