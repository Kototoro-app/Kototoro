package org.skepsun.kototoro.search.ui.compose

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import java.util.Locale
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchPreviewPane(
    content: Content,
    isLoading: Boolean = false,
    hasLoadError: Boolean = false,
    onClose: () -> Unit = {},
    onAddToFavorites: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenChapter: (ContentChapter) -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val sourceTitle = rememberResolvedSourceTitle(content.source)
    val coverRequest = rememberPreviewCoverRequest(content)
    val description = remember(content.description) { content.previewDescription() }
    val authors = remember(content.authors) { content.previewAuthors() }
    val previewGroups = remember(content.chapters) {
        content.resolvePreviewChapters()
    }
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val previewBackdrop = if (isIosStyle) rememberLayerBackdrop() else null
    val backdropModifier = if (previewBackdrop != null) {
        Modifier.layerBackdrop(previewBackdrop)
    } else {
        Modifier
    }
    val effectiveBackdrop = previewBackdrop ?: LocalLiquidGlassBackdrop.current

    CompositionLocalProvider(LocalLiquidGlassBackdrop provides effectiveBackdrop) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Full-bleed poster background
            AsyncImage(
                model = coverRequest,
                contentDescription = content.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onOpenDetails)
                    .then(backdropModifier),
            )

            // Vertical gradient scrim for depth and readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.45f),
                            0.20f to Color.Black.copy(alpha = 0.10f),
                            0.50f to Color.Black.copy(alpha = 0.35f),
                            1.0f to Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
            )

            // Top control bar: Back (left), Favorite and Details (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarControlSurface(
                    modifier = Modifier.size(42.dp),
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopBarControlSurface(
                        modifier = Modifier.size(42.dp),
                    ) {
                        IconButton(
                            onClick = onAddToFavorites,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_heart_outline),
                                contentDescription = stringResource(R.string.add_to_favourites),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    TopBarControlSurface(
                        modifier = Modifier.size(42.dp),
                    ) {
                        IconButton(
                            onClick = onOpenDetails,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_forward),
                                contentDescription = stringResource(R.string.details),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // Floating frosted glass card
            val cardShape = RoundedRectangle(28.dp)
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .fillMaxHeight(0.78f)
                    .align(Alignment.BottomCenter)
                    .clip(cardShape),
                shape = cardShape,
                style = GlassDefaults.prominentStyle(),
                componentRole = GlassComponentRole.ContentOverlay,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (hasLoadError) {
                        Text(
                            text = stringResource(R.string.preview_details_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Genre / Tag pills
                    if (content.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            content.tags.take(5).forEach { tag ->
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                ) {
                                    Text(
                                        text = tag.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    // Title
                    Text(
                        text = content.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenDetails),
                    )

                    // Author
                    if (authors.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = authors,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Stats row: Rating, Chapters count, State, Source
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (content.hasRating) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_book_page),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.chapters_count_info, totalChapters),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        content.state?.let { state ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            ) {
                                Text(
                                    text = stringResource(state.titleResId),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = sourceTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Action buttons: Start reading first chapter or Details + Favorite
                    val firstChapter = previewGroups.firstChapter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (firstChapter != null) {
                            Button(
                                onClick = { onOpenChapter(firstChapter) },
                                modifier = Modifier.weight(1f),
                                shape = CircleShape,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.start_reading),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            OutlinedButton(
                                onClick = onOpenDetails,
                                shape = CircleShape,
                            ) {
                                Text(
                                    text = stringResource(R.string.details),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        } else {
                            Button(
                                onClick = onOpenDetails,
                                modifier = Modifier.weight(1f),
                                shape = CircleShape,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.details),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onAddToFavorites,
                            shape = CircleShape,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_heart_outline),
                                contentDescription = stringResource(R.string.add_to_favourites),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Preserved Chapters Section: Earliest and Latest chapters
                    if (previewGroups.earliest.isNotEmpty() || previewGroups.latest.isNotEmpty()) {
                        if (previewGroups.isSingleGroup) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.chapters),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    val totalChapters = content.chapters?.size ?: 0
                                    if (totalChapters > previewGroups.earliest.size) {
                                        Text(
                                            text = stringResource(R.string.chapters_count_info, totalChapters),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable(onClick = onOpenDetails),
                                        )
                                    }
                                }
                                previewGroups.earliest.forEach { chapter ->
                                    SearchPreviewChapterRow(
                                        chapter = chapter,
                                        onClick = { onOpenChapter(chapter) },
                                    )
                                }
                            }
                        } else {
                            // Latest chapters
                            if (previewGroups.latest.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.preview_latest_chapters),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        val totalChapters = content.chapters?.size ?: 0
                                        if (totalChapters > 0) {
                                            Text(
                                                text = stringResource(R.string.chapters_count_info, totalChapters),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable(onClick = onOpenDetails),
                                            )
                                        }
                                    }
                                    previewGroups.latest.forEach { chapter ->
                                        SearchPreviewChapterRow(
                                            chapter = chapter,
                                            onClick = { onOpenChapter(chapter) },
                                        )
                                    }
                                }
                            }

                            // Earliest chapters
                            if (previewGroups.earliest.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.preview_first_chapters),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = stringResource(R.string.details),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable(onClick = onOpenDetails),
                                        )
                                    }
                                    previewGroups.earliest.forEach { chapter ->
                                        SearchPreviewChapterRow(
                                            chapter = chapter,
                                            onClick = { onOpenChapter(chapter) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Preserved Description Section
                    if (description.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.description),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Preserved Genres Section
                    if (content.tags.size > 5) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.genres),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                content.tags.forEach { tag ->
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    ) {
                                        Text(
                                            text = tag.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchFloatingPreviewCard(
    content: Content,
    isLoading: Boolean = false,
    hasLoadError: Boolean = false,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onAddToFavorites: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenChapter: (ContentChapter) -> Unit = {},
) {
    val sourceTitle = rememberResolvedSourceTitle(content.source)
    val coverRequest = rememberPreviewCoverRequest(content)
    val description = remember(content.description) { content.previewDescription() }
    val authors = remember(content.authors) { content.previewAuthors() }
    val coverShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sourceTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = content.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(132.dp)
                        .aspectRatio(0.68f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, coverShape)
                        .clip(coverShape)
                        .clickable(onClick = onOpenDetails),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = content.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenDetails),
                    )
                    if (authors.isNotBlank()) {
                        Text(
                            text = authors,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    content.state?.let { state ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(state.titleResId),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    content.chapters?.size?.takeIf { it > 0 }?.let { chapterCount ->
                        Text(
                            text = stringResource(R.string.chapters_count_info, chapterCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (content.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content.tags.take(6).forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    text = tag.title,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (hasLoadError) {
                Text(
                    text = stringResource(R.string.preview_details_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenDetails,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.details),
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onAddToFavorites,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_heart_outline),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.add_to_favourites),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberPreviewCoverRequest(content: Content): ImageRequest {
    val context = LocalContext.current
    return remember(content.id, content.largeCoverUrl, content.coverUrl, content.source) {
        ImageRequest.Builder(context)
            .data(content.largeCoverUrl ?: content.coverUrl)
            .mangaSourceExtra(content.source)
            .build()
    }
}

private fun Content.previewDescription(): String {
    return HtmlCompat.fromHtml(description.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
}

private fun Content.previewAuthors(): String = authors.take(2).joinToString(" · ")

@Composable
private fun SearchPreviewChapterRow(
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

