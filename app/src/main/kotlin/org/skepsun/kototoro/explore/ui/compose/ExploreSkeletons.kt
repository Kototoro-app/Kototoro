package org.skepsun.kototoro.explore.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.logHeroTransition
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.list.ui.model.ContentListModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun TrackingCompactPoster(
    item: ContentListModel,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    sharedElementKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val heroTransitionInProgress = LocalHeroTransitionInProgress.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val scoreChipColor = if (expressive) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    }
    val imageRequest = remember(item.coverUrl, item.id) {
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

    Column(
        modifier = modifier
            .width(posterStyle.itemWidth)
            .height(posterStyle.posterHeight + 32.dp)
            .clickable(
                onClick = {
                    logHeroTransition("explore_tracking_click title=${item.title} sharedKey=$sharedElementKey")
                    onClick()
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
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
                model = imageRequest,
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
                    color = scoreChipColor,
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun BrowseHeroSkeleton(
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CompactTopBarHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExploreSkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(184.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                ExploreSkeletonBlock(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun BrowseSourcesSkeleton(
    metrics: SourceQuickAccessMetrics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
            ) {
                repeat(metrics.preferredColumns.coerceAtMost(4)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExploreSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.cardHeight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowsePopularLoadingSection(
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(2) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExploreSkeletonBlock(
                        modifier = Modifier
                            .width(posterStyle.itemWidth)
                            .height(posterStyle.posterHeight),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExploreSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .height(16.dp),
                        )
                        ExploreSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.52f)
                                .height(14.dp),
                        )
                        ExploreSkeletonBlock(
                            modifier = Modifier
                                .width(88.dp)
                                .height(28.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreSkeletonBlock(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    )
}

