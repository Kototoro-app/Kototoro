package org.skepsun.kototoro.search.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import org.skepsun.kototoro.core.ui.preview.*
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

/** Android adapters supply source-aware Coil requests, localized values and the glass surface. */
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
    val groups = remember(content.chapters) { content.resolvePreviewChapters() }
    val count = content.chapters?.size ?: 0
    val firstSection = TabletPreviewSection(
        title = stringResource(if (groups.isSingleGroup) R.string.chapters else R.string.preview_latest_chapters),
        chapters = (if (groups.isSingleGroup) groups.earliest else groups.latest).map { previewChapterData(it) },
        countText = if (count > (if (groups.isSingleGroup) groups.earliest else groups.latest).size)
            stringResource(R.string.chapters_count_info, count) else null,
    )
    val sections = buildList {
        add(firstSection)
        if (!groups.isSingleGroup) add(TabletPreviewSection(
            stringResource(R.string.preview_first_chapters), groups.earliest.map { previewChapterData(it) },
        ))
    }
    val data = TabletPreviewData(
        id = content.id,
        title = content.title,
        authors = content.previewAuthors(),
        sourceTitle = rememberResolvedSourceTitle(content.source),
        description = remember(content.description) { content.previewDescription() },
        tags = content.tags.map { it.title },
        ratingText = if (content.hasRating) String.format(Locale.getDefault(), "%.1f", content.rating * 10f) else null,
        chapterCountText = if (count > 0) stringResource(R.string.chapters_count_info, count) else null,
        statusLabel = content.state?.let { stringResource(it.titleResId) },
        chapterSections = sections,
    )
    val labels = TabletPreviewLabels(
        close = stringResource(R.string.close), read = stringResource(R.string.read),
        details = stringResource(R.string.details), addToFavorites = stringResource(R.string.add_to_favourites),
        unavailable = stringResource(R.string.preview_details_unavailable), retry = stringResource(R.string.retry),
    )
    val request = rememberPreviewCoverRequest(content)
    val body: @Composable () -> Unit = {
        TabletPreviewContent(
            data = data, labels = labels,
            cover = { imageModifier -> AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = imageModifier) },
            icon = { icon -> when (icon) {
                TabletPreviewIcon.CLOSE -> rememberVectorPainter(Icons.Default.Close)
                TabletPreviewIcon.PLAY -> painterResource(R.drawable.ic_play)
                TabletPreviewIcon.FAVOURITE -> painterResource(R.drawable.ic_heart_outline)
                TabletPreviewIcon.RATING -> painterResource(R.drawable.ic_star_small)
            } },
            onClose = onClose, onRead = onRead, onOpenDetails = onOpenDetails, onAddToFavorites = onAddToFavorites,
            onOpenChapter = { id -> content.chapters?.firstOrNull { it.id == id }?.let(onOpenChapter) },
            isLoading = isLoading, hasLoadError = hasLoadError, onRetry = onRetry,
        )
    }
    if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
        val shape = RoundedRectangle(28.dp)
        GlassSurface(modifier.clip(shape), shape = shape, style = GlassDefaults.prominentStyle(),
            componentRole = GlassComponentRole.BottomPanel) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = GLASS_PANEL_VEIL_ALPHA))) {
                body()
            }
        }
    } else TabletPreviewSurface(modifier) { body() }
}
