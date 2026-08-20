package org.skepsun.kototoro.explore.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.logHeroTransition
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.secondaryTitleText
import org.skepsun.kototoro.list.ui.model.supportingText
import org.skepsun.kototoro.list.ui.model.buildInfoText

@Composable
internal fun TrackingCategoryRow(
    rowKey: String,
    title: String,
    items: List<ContentListModel>,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    onItemClick: (ContentListModel, String) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val rowState = rememberSaveable(rowKey, saver = LazyListState.Saver) {
        LazyListState()
    }
    val scrollIntensity = rememberHorizontalRailScrollIntensity(rowState)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMoreClick) {
                Text(stringResource(R.string.more))
            }
        }
        val railAnimationFactor = rememberRailAnimationFactor()
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
            ) { index, item ->
                HorizontalRailAnimatedVisibility(
                    animationKey = "explore_${title}_${item.id}",
                    index = index,
                    listState = rowState,
                    scrollIntensity = scrollIntensity,
                    animationFactor = railAnimationFactor,
                    enableScrollLinkedAnimation = false,
                ) { animatedModifier ->
                    val sharedElementKey = contentCoverSharedKey(
                        item.manga.source.name,
                        item.manga.coverUrl.orEmpty(),
                        instanceKey = "explore_row_${title}_${item.id}_$index",
                    )
                    TrackingCompactPoster(
                        item = item,
                        posterStyle = posterStyle,
                        sharedElementKey = sharedElementKey,
                        onClick = { onItemClick(item, sharedElementKey) },
                        modifier = animatedModifier,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BrowsePopularHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BrowsePopularListItem(
    item: ContentListModel,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    sharedElementKey: String,
    panoramaCoverBlur: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val heroTransitionInProgress = LocalHeroTransitionInProgress.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val surfaceShape = RoundedCornerShape(if (expressive) 28.dp else 24.dp)
    val chipColor = if (expressive) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    }
    val backgroundRequest = remember(item.coverUrl, item.id, panoramaCoverBlur) {
        buildExploreCoverRequest(
            context = context,
            coverUrl = item.coverUrl,
            content = item.manga,
            size = 150,
            blurPercent = panoramaCoverBlur,
        )
    }
    val posterRequest = remember(item.coverUrl, item.id) {
        buildExploreCoverRequest(
            context = context,
            coverUrl = item.coverUrl,
            content = item.manga,
            size = 320,
            sharedMemoryCacheKey = sharedCoverMemoryCacheKey(
                sourceName = item.manga.source.name,
                ownerKey = item.manga.url,
                url = item.coverUrl,
            ),
            crossfadeEnabled = !heroTransitionInProgress,
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClick = {
                    logHeroTransition("explore_popular_click title=${item.title} sharedKey=$sharedElementKey")
                    onClick()
                },
            ),
        shape = surfaceShape,
        color = if (expressive) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.16f)
        },
        tonalElevation = if (expressive) 0.dp else 1.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp),
        ) {
            AsyncImage(
                model = backgroundRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.58f),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.14f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.90f),
                            ),
                        ),
                    )
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                            ),
                        ),
                    ),
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(posterStyle.itemWidth)
                        .height(posterStyle.posterHeight)
                        .then(
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = sharedElementKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    )
                                }
                            } else Modifier
                        )
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(posterStyle.cornerRadius))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onSuccess = { state ->
                            HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
                        },
                    )
                    item.scoreText?.takeIf { it.isNotBlank() }?.let { scoreText ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = chipColor,
                        ) {
                            Text(
                                text = scoreText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val infoText = remember(item.manga.state, item.manga.chapters?.size, item.manga.tags, item.scoreText, context) {
                        item.buildInfoText(context)
                    }
                    infoText?.takeIf { it.isNotBlank() }?.let { info ->
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.secondaryTitleText()?.takeIf { it.isNotBlank() }?.let { secondaryTitle ->
                        Text(
                            text = secondaryTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.supportingText()?.takeIf { it.isNotBlank() }?.let { supportingText ->
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (expressive) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
                        },
                    ) {
                        Text(
                            text = item.source.getTitle(context),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

