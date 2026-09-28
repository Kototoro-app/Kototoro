package org.skepsun.kototoro.search.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import java.util.Locale
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

private const val PREVIEW_TAG_LIMIT = 6
private const val PREVIEW_DESCRIPTION_LINES = 4

/**
 * The tablet works list's preview: a floating card with a cover header (blurred art behind a sharp
 * thumbnail, so the title stays readable whatever the cover) over an opaque body.
 */
@Composable
internal fun SearchPreviewCard(
    content: Content,
    isLoading: Boolean,
    hasLoadError: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onRead: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddToFavorites: () -> Unit,
    onOpenChapter: (ContentChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedRectangle(28.dp)
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchPreviewHeader(content = content, onClose = onClose, onOpenDetails = onOpenDetails)
            SearchPreviewBody(
                content = content,
                isLoading = isLoading,
                hasLoadError = hasLoadError,
                onRetry = onRetry,
                onRead = onRead,
                onOpenDetails = onOpenDetails,
                onAddToFavorites = onAddToFavorites,
                onOpenChapter = onOpenChapter,
            )
        }
    }
    if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
        GlassSurface(
            modifier = modifier.clip(cardShape),
            shape = cardShape,
            style = GlassDefaults.prominentStyle(),
            componentRole = GlassComponentRole.BottomPanel,
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = GLASS_PANEL_VEIL_ALPHA))) {
                body()
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            body()
        }
    }
}

@Composable
private fun SearchPreviewHeader(
    content: Content,
    onClose: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val coverRequest = rememberPreviewCoverRequest(content)
    val sourceTitle = rememberResolvedSourceTitle(content.source)
    val authors = remember(content.authors) { content.previewAuthors() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .blur(24.dp),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.65f),
                    ),
                ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            AsyncImage(
                model = coverRequest,
                contentDescription = content.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 112.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenDetails),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 28.dp),
            ) {
                Text(
                    text = content.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenDetails),
                )
                if (authors.isNotBlank()) {
                    Text(
                        text = authors,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = sourceTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPreviewBody(
    content: Content,
    isLoading: Boolean,
    hasLoadError: Boolean,
    onRetry: () -> Unit,
    onRead: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddToFavorites: () -> Unit,
    onOpenChapter: (ContentChapter) -> Unit,
) {
    val description = remember(content.description) { content.previewDescription() }
    val previewGroups = remember(content.chapters) { content.resolvePreviewChapters() }
    var descriptionExpanded by rememberSaveable(content.id) { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (hasLoadError) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.preview_details_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        if (content.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxLines = 1,
            ) {
                content.tags.take(PREVIEW_TAG_LIMIT).forEach { tag -> SearchPreviewTag(tag.title) }
                if (content.tags.size > PREVIEW_TAG_LIMIT) {
                    SearchPreviewTag("+${content.tags.size - PREVIEW_TAG_LIMIT}")
                }
            }
        }
        SearchPreviewStats(content)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(onClick = onRead, shape = CircleShape, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.read), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onOpenDetails, shape = CircleShape) {
                Text(stringResource(R.string.details))
            }
            OutlinedIconButton(onClick = onAddToFavorites) {
                Icon(
                    painter = painterResource(R.drawable.ic_heart_outline),
                    contentDescription = stringResource(R.string.add_to_favourites),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else PREVIEW_DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { descriptionExpanded = !descriptionExpanded },
            )
        }
        SearchPreviewChapterSection(
            title = stringResource(
                if (previewGroups.isSingleGroup) R.string.chapters else R.string.preview_latest_chapters,
            ),
            chapters = if (previewGroups.isSingleGroup) previewGroups.earliest else previewGroups.latest,
            totalChapters = content.chapters?.size ?: 0,
            onOpenDetails = onOpenDetails,
            onOpenChapter = onOpenChapter,
        )
        if (!previewGroups.isSingleGroup) {
            SearchPreviewChapterSection(
                title = stringResource(R.string.preview_first_chapters),
                chapters = previewGroups.earliest,
                totalChapters = 0,
                onOpenDetails = onOpenDetails,
                onOpenChapter = onOpenChapter,
            )
        }
    }
}

@Composable
private fun SearchPreviewChapterSection(
    title: String,
    chapters: List<ContentChapter>,
    totalChapters: Int,
    onOpenDetails: () -> Unit,
    onOpenChapter: (ContentChapter) -> Unit,
) {
    if (chapters.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (totalChapters > chapters.size) {
                Text(
                    text = stringResource(R.string.chapters_count_info, totalChapters),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenDetails),
                )
            }
        }
        chapters.forEach { chapter ->
            SearchPreviewChapterRow(chapter = chapter, onClick = { onOpenChapter(chapter) })
        }
    }
}

@Composable
private fun SearchPreviewStats(content: Content) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (content.hasRating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_star_small),
                    contentDescription = null,
                    tint = Color(0xFFFFB800),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = String.format(Locale.getDefault(), "%.1f", content.rating * 10f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val totalChapters = content.chapters?.size ?: 0
        if (totalChapters > 0) {
            Text(
                text = stringResource(R.string.chapters_count_info, totalChapters),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content.state?.let { state ->
            SearchPreviewTag(stringResource(state.titleResId))
        }
    }
}

@Composable
private fun SearchPreviewTag(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
