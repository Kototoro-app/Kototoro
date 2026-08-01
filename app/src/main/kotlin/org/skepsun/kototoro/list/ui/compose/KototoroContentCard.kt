package org.skepsun.kototoro.list.ui.compose

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import coil3.compose.AsyncImage
import coil3.network.HttpException
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.ContentSourceResolvedIcon
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverCornerRadius
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.compose.ContentCoverCornerRadius
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.secondaryTitleText
import org.skepsun.kototoro.list.ui.model.supportingText
import org.skepsun.kototoro.list.ui.model.buildInfoText
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.rememberResolvedContentSource
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import androidx.compose.animation.ExperimentalSharedTransitionApi
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.contentCoverIdentity
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.image.tvboxSearchCoverModel
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.CHAPTERS_LEFT
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.CHAPTERS_READ
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.NONE
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.PERCENT_LEFT
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.PERCENT_READ
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "KototoroContentCard"

@Immutable
data class ContentCardBadgeMetrics(
    val containerHorizontalPadding: androidx.compose.ui.unit.Dp = 7.dp,
    val containerVerticalPadding: androidx.compose.ui.unit.Dp = 4.dp,
    val itemSpacing: androidx.compose.ui.unit.Dp = 4.dp,
    val iconSize: androidx.compose.ui.unit.Dp = 14.dp,
    val textSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    val outerPadding: androidx.compose.ui.unit.Dp = 7.dp,
    val badgeEdgePadding: androidx.compose.ui.unit.Dp = 0.dp,
    val progressSize: androidx.compose.ui.unit.Dp = 32.dp,
    val progressAnchorInset: androidx.compose.ui.unit.Dp = 8.dp,
    val progressSpacing: androidx.compose.ui.unit.Dp = 4.dp,
    val innerCornerRadius: androidx.compose.ui.unit.Dp = 10.dp,
)

fun contentCardBadgeMetricsFor(coverWidth: androidx.compose.ui.unit.Dp): ContentCardBadgeMetrics {
    val scale = (coverWidth.value / 112f).coerceIn(0.66f, 1.15f)
    val isSmallCard = coverWidth < 80.dp
    return ContentCardBadgeMetrics(
        containerHorizontalPadding = 7.dp * scale,
        containerVerticalPadding = 4.dp * scale,
        itemSpacing = 4.dp * scale,
        iconSize = 14.dp * scale,
        textSize = 10.sp * scale,
        outerPadding = 7.dp * scale,
        badgeEdgePadding = 0.dp,
        progressSize = if (isSmallCard) 24.dp else 32.dp,
        progressAnchorInset = 8.dp * scale,
        progressSpacing = 4.dp * scale,
        innerCornerRadius = 10.dp * scale,
    )
}

@Immutable
data class ContentCardUiPrefs(
    val badgesTopLeft: Set<String>,
    val badgesTopRight: Set<String>,
    val badgesBottomLeft: Set<String>,
    val badgesBottomRight: Set<String>,
    val showExtraInfo: Boolean = false,
)

@Composable
fun rememberContentCardUiPrefs(
    settings: AppSettings,
): ContentCardUiPrefs {
    val prefs by settings.observeAsState(
        AppSettings.KEY_BADGES_TOP_LEFT,
        AppSettings.KEY_BADGES_TOP_RIGHT,
        AppSettings.KEY_BADGES_BOTTOM_LEFT,
        AppSettings.KEY_BADGES_BOTTOM_RIGHT,
        AppSettings.KEY_SHOW_EXTRA_INFO_ON_CARDS,
    ) {
        ContentCardUiPrefs(
            badgesTopLeft = badgesTopLeft,
            badgesTopRight = badgesTopRight,
            badgesBottomLeft = badgesBottomLeft,
            badgesBottomRight = badgesBottomRight,
            showExtraInfo = showExtraInfoOnCards,
        )
    }
    return prefs
}

@Composable
fun KototoroContentCard(
    model: ContentListModel,
    isListLayout: Boolean = false,
    isSelected: Boolean = false,
    selectionModeActive: Boolean = false,
    sharedTransitionEnabled: Boolean = true,
    sharedElementInstanceKey: String? = null,
    cardStyle: CompactPosterCardStyle? = null,
    uiPrefs: ContentCardUiPrefs? = null,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isListLayout) {
        if (model is ContentDetailedListModel) {
            KototoroContentCardDetailedList(
                item = model,
                isSelected = isSelected,
                sharedTransitionEnabled = sharedTransitionEnabled,
                sharedElementInstanceKey = sharedElementInstanceKey,
                uiPrefs = uiPrefs,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier
            )
        } else if (model is ContentCompactListModel) {
            KototoroContentCardList(
                item = model,
                isSelected = isSelected,
                sharedTransitionEnabled = sharedTransitionEnabled,
                sharedElementInstanceKey = sharedElementInstanceKey,
                uiPrefs = uiPrefs,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier
            )
        }
    } else {
        if (model is ContentGridModel) {
            KototoroContentCardGrid(
                item = model,
                isSelected = isSelected,
                sharedTransitionEnabled = sharedTransitionEnabled,
                sharedElementInstanceKey = sharedElementInstanceKey,
                cardStyle = cardStyle,
                uiPrefs = uiPrefs,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun KototoroContentCardGrid(
    item: ContentGridModel,
    isSelected: Boolean = false,
    showSourceInfo: Boolean = false, // Ignored in favor of new badge settings
    gridScale: Float = 1f,
    sharedTransitionEnabled: Boolean = true,
    sharedElementInstanceKey: String? = null,
    cardStyle: CompactPosterCardStyle? = null,
    compactOverlay: Boolean = false,
    cellContentPadding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    uiPrefs: ContentCardUiPrefs? = null,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val resolvedUiPrefs = uiPrefs ?: run {
        val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
        rememberContentCardUiPrefs(settings)
    }

    val manga = item.manga
    val coverRequest = rememberContentCoverRequest(
        context = context,
        coverUrl = item.coverUrl,
        manga = manga,
        allowCrossfade = !sharedTransitionEnabled,
    )
    val posterStyle = cardStyle ?: compactPosterCardStyle(gridScale)
    val posterAspectRatio = remember(posterStyle.itemWidth, posterStyle.posterHeight) {
        posterStyle.itemWidth.value / posterStyle.posterHeight.value
    }
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    val badgeMetrics = remember(posterStyle.itemWidth) { contentCardBadgeMetricsFor(posterStyle.itemWidth) }
    val compactTitleHeight = remember(posterStyle.itemWidth) {
        (posterStyle.itemWidth.value * 0.48f).dp.coerceIn(50.dp, 66.dp)
    }
    val compactTitleTextClearance = remember(posterStyle.itemWidth) {
        (posterStyle.itemWidth.value * 0.34f).dp.coerceIn(36.dp, 48.dp)
    }
    val titleFontSize = resolveGridTitleFontSize(gridScale)
    val bottomBadgeLift = if (compactOverlay) compactTitleTextClearance else 0.dp
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedIdentity = remember(manga.id, manga.url, manga.publicUrl, item.coverUrl) {
        contentCoverIdentity(manga, item.coverUrl)
    }
    val sharedKey = remember(manga.source.name, sharedIdentity, sharedElementInstanceKey) {
        contentCoverSharedKey(manga.source.name, sharedIdentity, sharedElementInstanceKey)
    }
    
    val cardShape = RoundedCornerShape(posterStyle.cornerRadius)
    val cardRadius = posterStyle.cornerRadius

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .combinedClickable(
                onClick = { onClick(coverBounds) },
                onLongClick = onLongClick,
            )
            .padding(cellContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(posterAspectRatio)
                .then(
                    if (sharedTransitionEnabled) {
                        Modifier.onGloballyPositioned { coordinates ->
                            coverBounds = coordinates.unclippedBoundsInWindow()
                        }
                    } else Modifier,
                )
                .then(
                    if (sharedTransitionEnabled && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(
                                    key = sharedKey,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    } else Modifier,
                )
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ContentCardCoverImage(
                coverRequest = coverRequest,
                contentDescription = manga.title,
                sharedKey = sharedKey,
            )

            if (isSelected) {
                Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.Center).size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(4.dp)
                )
            }

            // Top Left Badges
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesTopLeft,
                item = item,
                corner = Alignment.TopStart,
                cardRadius = cardRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // Top Right Badges (includes counter if not handled by badges)
            val effectiveTopRightBadges = remember(resolvedUiPrefs.badgesTopRight, item.counter, item.scoreText) {
                buildSet {
                    addAll(resolvedUiPrefs.badgesTopRight)
                    if (item.counter > 0) {
                        add("counter")
                    }
                    if (!item.scoreText.isNullOrBlank()) {
                        add("score")
                    }
                }
            }
            ContentCardCornerBadges(
                badges = effectiveTopRightBadges,
                item = item,
                corner = Alignment.TopEnd,
                cardRadius = cardRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            val showBottomRightBadge = remember(resolvedUiPrefs.badgesBottomRight, item.manga) {
                "nsfw" in resolvedUiPrefs.badgesBottomRight && item.manga.isNsfw()
            }
            val bottomBadgeHeight = with(density) { badgeMetrics.textSize.toDp() } +
                (badgeMetrics.containerVerticalPadding * 2)

            // Bottom Left Badges
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesBottomLeft,
                item = item,
                corner = Alignment.BottomStart,
                cardRadius = cardRadius,
                metrics = badgeMetrics,
                attachedToTitleEdge = compactOverlay,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = bottomBadgeLift),
            )

            val badgeReservedHeight = if (showBottomRightBadge) {
                bottomBadgeHeight + badgeMetrics.progressSpacing
            } else {
                0.dp
            }
            if (showBottomRightBadge) {
                ContentCardCornerBadges(
                    badges = resolvedUiPrefs.badgesBottomRight,
                    item = item,
                    corner = Alignment.BottomEnd,
                    cardRadius = cardRadius,
                    metrics = badgeMetrics,
                    attachedToTitleEdge = compactOverlay,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = badgeMetrics.badgeEdgePadding,
                            bottom = bottomBadgeLift + badgeMetrics.badgeEdgePadding,
                        ),
                )
            }
            if (item.progress != null) {
                ContentCardReadingProgressIndicator(
                    progress = item.progress,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = badgeMetrics.progressAnchorInset,
                            bottom = badgeMetrics.progressAnchorInset + badgeReservedHeight + bottomBadgeLift,
                        )
                        .size(badgeMetrics.progressSize),
                )
            }

            if (compactOverlay) {
                CompactGridTitleOverlay(
                    title = item.title,
                    height = compactTitleHeight,
                    fontSize = titleFontSize,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        if (!compactOverlay) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = titleFontSize,
                    lineHeight = (titleFontSize.value + 3f).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
        if (!compactOverlay && resolvedUiPrefs.showExtraInfo) {
            val infoText = remember(item.manga.state, item.manga.chapters?.size, item.manga.tags, item.scoreText, context) {
                item.buildInfoText(context)
            }
            infoText?.takeIf { it.isNotBlank() }?.let { info ->
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }

        if (!item.subtitle.isNullOrBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
        }
    }
}

@Composable
private fun CompactGridTitleOverlay(
    title: String,
    height: androidx.compose.ui.unit.Dp,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val overlayBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.72f),
            ),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(overlayBrush)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = fontSize,
                lineHeight = (fontSize.value + 3f).sp,
            ),
            color = Color.White,
            softWrap = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun resolveGridTitleFontSize(gridScale: Float): TextUnit {
    val normalized = ((gridScale.coerceIn(0.5f, 1.5f) - 0.5f) / 1f).coerceIn(0f, 1f)
    return (10f + 4f * normalized).sp
}

@Composable
fun ContentCardReadingProgressIndicator(
    progress: ReadingProgress,
    modifier: Modifier = Modifier,
) {
    if (!progress.isValid()) return

    val percent = progress.percent.coerceIn(0f, 1f)
    val strokeColor = MaterialTheme.colorScheme.surfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onPrimary
    val label = remember(progress) {
        when (progress.mode) {
            NONE -> ""
            PERCENT_READ -> "${ReadingProgress.percentToString(progress.percent)}%"
            PERCENT_LEFT -> "-${ReadingProgress.percentToString(progress.percentLeft)}%"
            CHAPTERS_READ -> progress.chapters.toString()
            CHAPTERS_LEFT -> "-${progress.chaptersLeft}"
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.125f
            val radius = size.minDimension / 2f
            val arcDiameter = size.minDimension - strokeWidth

            drawCircle(
                color = backgroundColor,
                radius = radius,
            )
            drawArc(
                color = strokeColor,
                startAngle = -90f,
                sweepAngle = 360f * percent,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2f, strokeWidth / 2f),
                size = androidx.compose.ui.geometry.Size(arcDiameter, arcDiameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        if (progress.isCompleted()) {
            Icon(
                painter = painterResource(id = R.drawable.ic_check),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.fillMaxSize(0.45f),
            )
        } else {
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun KototoroContentCardList(
    item: org.skepsun.kototoro.list.ui.model.ContentCompactListModel,
    isSelected: Boolean = false,
    sharedTransitionEnabled: Boolean = true,
    sharedElementInstanceKey: String? = null,
    uiPrefs: ContentCardUiPrefs? = null,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedUiPrefs = uiPrefs ?: run {
        val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
        rememberContentCardUiPrefs(settings)
    }
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    val coverRequest = rememberContentCoverRequest(
        context = context,
        coverUrl = item.coverUrl,
        manga = item.manga,
        allowCrossfade = !sharedTransitionEnabled,
    )
    val badgeModel = remember(item) { item.asBadgeModel() }
    val badgeMetrics = remember { contentCardBadgeMetricsFor(48.dp) }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedIdentity = remember(item.manga.id, item.manga.url, item.manga.publicUrl, item.coverUrl) {
        contentCoverIdentity(item.manga, item.coverUrl)
    }
    val sharedKey = remember(item.manga.source.name, sharedIdentity, sharedElementInstanceKey) {
        contentCoverSharedKey(item.manga.source.name, sharedIdentity, sharedElementInstanceKey)
    }
    val effectiveTopRightBadges = remember(resolvedUiPrefs.badgesTopRight, item.counter, item.scoreText) {
        buildSet {
            addAll(resolvedUiPrefs.badgesTopRight)
            if (item.counter > 0) {
                add("counter")
            }
            if (!item.scoreText.isNullOrBlank()) {
                add("score")
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .combinedClickable(
                onClick = { onClick(coverBounds) },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp, 72.dp)
                .then(
                    if (sharedTransitionEnabled) {
                        Modifier.onGloballyPositioned { coordinates ->
                            coverBounds = coordinates.unclippedBoundsInWindow()
                        }
                    } else Modifier,
                )
                .then(
                    if (sharedTransitionEnabled && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(
                                    key = sharedKey,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    } else Modifier,
                )
                .clip(CompactContentCoverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ContentCardCoverImage(
                coverRequest = coverRequest,
                contentDescription = item.title,
                sharedKey = sharedKey,
            )
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesTopLeft,
                item = badgeModel,
                corner = Alignment.TopStart,
                cardRadius = CompactContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopStart),
            )
            ContentCardCornerBadges(
                badges = effectiveTopRightBadges,
                item = badgeModel,
                corner = Alignment.TopEnd,
                cardRadius = CompactContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesBottomLeft,
                item = badgeModel,
                corner = Alignment.BottomStart,
                cardRadius = CompactContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesBottomRight,
                item = badgeModel,
                corner = Alignment.BottomEnd,
                cardRadius = CompactContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
            ContentCardCoverProgressIndicator(
                progress = badgeModel.progress,
                bottomRightBadges = resolvedUiPrefs.badgesBottomRight,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!item.supportingText.isNullOrBlank()) {
                Text(
                    text = item.supportingText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun ContentCardCornerBadges(
    badges: Set<String>,
    item: ContentGridModel,
    corner: Alignment,
    cardRadius: androidx.compose.ui.unit.Dp,
    metrics: ContentCardBadgeMetrics = ContentCardBadgeMetrics(),
    attachedToTitleEdge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return

    val resolvedSource = rememberResolvedContentSource(item.manga.source)
    val langText = remember(resolvedSource.name, resolvedSource.locale) {
        resolvedSource.getLocale()
            ?.language
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
    }
    val showTracker = "tracker" in badges && item.metadataTrackingService != null
    val showFavorite = "favorite" in badges && item.isFavorite
    val showSaved = "saved" in badges && item.isSaved
    val showSource = "source" in badges
    val showLanguage = "language" in badges && !langText.isNullOrBlank()
    val showCounter = "counter" in badges && item.counter > 0
    val showProjectionCount = "projection_count" in badges && item.projectionCount > 1
    val showScore = "score" in badges && !item.scoreText.isNullOrBlank()
    val showPin = "pin" in badges && item.isPinned
    val showNsfw = "nsfw" in badges && item.manga.isNsfw()
    val showOnlyNsfw = showNsfw &&
        !showTracker &&
        !showFavorite &&
        !showSaved &&
        !showSource &&
        !showLanguage &&
        !showCounter &&
        !showProjectionCount &&
        !showScore &&
        !showPin

    if (
        !showTracker &&
        !showFavorite &&
        !showSaved &&
        !showSource &&
        !showLanguage &&
        !showCounter &&
        !showProjectionCount &&
        !showScore &&
        !showNsfw &&
        !showPin
    ) {
        return
    }

    val shape = if (attachedToTitleEdge) {
        when (corner) {
            Alignment.BottomStart -> RoundedCornerShape(
                topEnd = metrics.innerCornerRadius,
                bottomEnd = metrics.innerCornerRadius,
            )
            Alignment.BottomEnd -> RoundedCornerShape(
                topStart = metrics.innerCornerRadius,
                bottomStart = metrics.innerCornerRadius,
            )
            else -> RoundedCornerShape(metrics.innerCornerRadius)
        }
    } else {
        when (corner) {
            Alignment.TopStart -> RoundedCornerShape(topStart = cardRadius, bottomEnd = metrics.innerCornerRadius)
            Alignment.TopEnd -> RoundedCornerShape(topEnd = cardRadius, bottomStart = metrics.innerCornerRadius)
            Alignment.BottomStart -> RoundedCornerShape(bottomStart = cardRadius, topEnd = metrics.innerCornerRadius)
            Alignment.BottomEnd -> RoundedCornerShape(bottomEnd = cardRadius, topStart = metrics.innerCornerRadius)
            else -> RoundedCornerShape(metrics.innerCornerRadius)
        }
    }

    Row(
        modifier = modifier
            .background(
                color = if (showOnlyNsfw) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.96f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                },
                shape = shape,
            )
            .padding(
                horizontal = metrics.containerHorizontalPadding,
                vertical = metrics.containerVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.itemSpacing)
    ) {
        badges.forEach { badge ->
            when (badge) {
                "tracker" -> {
                    item.metadataTrackingService?.let { service ->
                        Icon(
                            painter = rememberSafePainter(service.iconResId),
                            contentDescription = service.name,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(metrics.iconSize),
                        )
                    }
                }
                "favorite" -> {
                    if (item.isFavorite) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_heart_outline),
                            contentDescription = "Favourite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(metrics.iconSize),
                        )
                    }
                }
                "saved" -> {
                    if (item.isSaved) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_storage),
                            contentDescription = "Local/Saved",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(metrics.iconSize),
                        )
                    }
                }
                "source" -> {
                    ContentSourceResolvedIcon(
                        source = resolvedSource,
                        contentDescription = resolvedSource.name,
                        modifier = Modifier.size(metrics.iconSize),
                    )
                }
                "language" -> {
                    if (!langText.isNullOrBlank()) {
                        Text(
                            text = langText,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = metrics.textSize,
                                lineHeight = metrics.textSize,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                "counter" -> {
                    if (item.counter > 0) {
                        Text(
                            text = item.counter.toString(),
                            color = if (showOnlyNsfw) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = metrics.textSize,
                                lineHeight = metrics.textSize,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                "projection_count" -> {
                    if (item.projectionCount > 1) {
                        Text(
                            text = "x${item.projectionCount}",
                            color = if (showOnlyNsfw) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = metrics.textSize,
                                lineHeight = metrics.textSize,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                "score" -> {
                    item.scoreText?.takeIf { it.isNotBlank() }?.let { scoreText ->
                        Text(
                            text = scoreText,
                            color = if (showOnlyNsfw) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = metrics.textSize,
                                lineHeight = metrics.textSize,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                "nsfw" -> {
                    if (item.manga.isNsfw()) {
                        Text(
                            text = stringResource(R.string.badge_nsfw),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = metrics.textSize,
                                lineHeight = metrics.textSize,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                "pin" -> {
                    if (item.isPinned) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pin),
                            contentDescription = stringResource(R.string.pin),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(metrics.iconSize),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun KototoroContentCardDetailedList(
    item: org.skepsun.kototoro.list.ui.model.ContentDetailedListModel,
    isSelected: Boolean = false,
    sharedTransitionEnabled: Boolean = true,
    sharedElementInstanceKey: String? = null,
    uiPrefs: ContentCardUiPrefs? = null,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedUiPrefs = uiPrefs ?: run {
        val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
        rememberContentCardUiPrefs(settings)
    }
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    val coverRequest = rememberContentCoverRequest(
        context = context,
        coverUrl = item.coverUrl,
        manga = item.manga,
        allowCrossfade = !sharedTransitionEnabled,
    )
    val badgeModel = remember(item) {
        item.asBadgeModel(
            isFavorite = item.isFavorite,
            isSaved = item.isSaved,
        )
    }
    val badgeMetrics = remember { contentCardBadgeMetricsFor(80.dp) }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedIdentity = remember(item.manga.id, item.manga.url, item.manga.publicUrl, item.coverUrl) {
        contentCoverIdentity(item.manga, item.coverUrl)
    }
    val sharedKey = remember(item.manga.source.name, sharedIdentity, sharedElementInstanceKey) {
        contentCoverSharedKey(item.manga.source.name, sharedIdentity, sharedElementInstanceKey)
    }
    val effectiveTopRightBadges = remember(resolvedUiPrefs.badgesTopRight, item.counter, item.scoreText) {
        buildSet {
            addAll(resolvedUiPrefs.badgesTopRight)
            if (item.counter > 0) {
                add("counter")
            }
            if (!item.scoreText.isNullOrBlank()) {
                add("score")
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .combinedClickable(
                onClick = { onClick(coverBounds) },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp, 120.dp)
                .then(
                    if (sharedTransitionEnabled) {
                        Modifier.onGloballyPositioned { coordinates ->
                            coverBounds = coordinates.unclippedBoundsInWindow()
                        }
                    } else Modifier,
                )
                .then(
                    if (sharedTransitionEnabled && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(
                                    key = sharedKey,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    } else Modifier,
                )
                .clip(ContentCoverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ContentCardCoverImage(
                coverRequest = coverRequest,
                contentDescription = item.title,
                sharedKey = sharedKey,
            )
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesTopLeft,
                item = badgeModel,
                corner = Alignment.TopStart,
                cardRadius = ContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopStart),
            )
            ContentCardCornerBadges(
                badges = effectiveTopRightBadges,
                item = badgeModel,
                corner = Alignment.TopEnd,
                cardRadius = ContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesBottomLeft,
                item = badgeModel,
                corner = Alignment.BottomStart,
                cardRadius = ContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            ContentCardCornerBadges(
                badges = resolvedUiPrefs.badgesBottomRight,
                item = badgeModel,
                corner = Alignment.BottomEnd,
                cardRadius = ContentCoverCornerRadius,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
            ContentCardCoverProgressIndicator(
                progress = badgeModel.progress,
                bottomRightBadges = resolvedUiPrefs.badgesBottomRight,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
			val authorText = remember(item.manga.authors) {
				item.manga.authors.joinToString(", ")
			}
			if (!item.subtitle.isNullOrBlank()) {
				Text(
					text = item.subtitle.orEmpty(),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!item.supportingText.isNullOrBlank()) {
                Text(
                    text = item.supportingText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (authorText.isNotBlank()) {
                Text(
                    text = authorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            val tagsText = remember(item.tags) {
                item.tags.joinToString(", ") { it.title ?: "" }
            }
            if (tagsText.isNotBlank()) {
                Text(
                    text = tagsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

fun ContentListModel.asBadgeModel(
    isFavorite: Boolean = false,
    isSaved: Boolean = false,
): ContentGridModel {
    return ContentGridModel(
        manga = manga,
        override = override,
        subtitle = null,
        counter = counter,
        projectionCount = projectionCount,
        id = id,
        progress = when (this) {
            is ContentGridModel -> progress
            is ContentDetailedListModel -> progress
            is ContentCompactListModel -> progress
            else -> null
        },
        isFavorite = isFavorite,
        isSaved = isSaved,
        isPinned = isPinned,
        metadataTrackingService = metadataTrackingService,
        scoreText = scoreText,
    )
}

@Composable
fun ContentCardCoverProgressIndicator(
    progress: ReadingProgress?,
    bottomRightBadges: Set<String>,
    metrics: ContentCardBadgeMetrics = ContentCardBadgeMetrics(),
    modifier: Modifier = Modifier,
) {
    progress ?: return
    val badgeReservedHeight = if (bottomRightBadges.isNotEmpty()) {
        with(androidx.compose.ui.platform.LocalDensity.current) { metrics.textSize.toDp() } +
            (metrics.containerVerticalPadding * 2) +
            metrics.progressSpacing
    } else {
        0.dp
    }
    ContentCardReadingProgressIndicator(
        progress = progress,
        modifier = modifier
            .padding(
                end = metrics.progressAnchorInset,
                bottom = metrics.progressAnchorInset + badgeReservedHeight,
            )
            .size(metrics.progressSize),
    )
}

@Composable
fun ContentCardNsfwBadge(
    metrics: ContentCardBadgeMetrics = ContentCardBadgeMetrics(),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.96f),
                shape = RoundedCornerShape(metrics.innerCornerRadius),
            )
            .padding(
                horizontal = metrics.containerHorizontalPadding,
                vertical = metrics.containerVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.badge_nsfw),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = metrics.textSize,
                lineHeight = metrics.textSize,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

@Composable
private fun BoxScope.ContentCardCoverImage(
    coverRequest: ImageRequest?,
    contentDescription: String,
    sharedKey: String,
) {
    if (coverRequest == null) {
        return
    }
    AsyncImage(
        model = coverRequest,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
        onSuccess = { state ->
            HeroCoverSnapshotStore.put(sharedKey, state.result.image)
        },
    )
}

@Composable
private fun rememberContentCoverRequest(
    context: android.content.Context,
    coverUrl: String?,
    manga: org.skepsun.kototoro.parsers.model.Content,
    allowCrossfade: Boolean = true,
): ImageRequest? {
    var failureVersion by remember(coverUrl, manga.id, manga.source) { mutableStateOf(0) }
    return remember(context, coverUrl, manga.id, manga.source, allowCrossfade, failureVersion) {
        buildContentCoverRequest(
            context = context,
            coverUrl = coverUrl,
            manga = manga,
            allowCrossfade = allowCrossfade,
            onRecoverableFailure = { failureVersion++ },
        )
    }
}

private fun buildContentCoverRequest(
    context: android.content.Context,
    coverUrl: String?,
    manga: org.skepsun.kototoro.parsers.model.Content,
    allowCrossfade: Boolean = true,
    onRecoverableFailure: () -> Unit = {},
): ImageRequest? {
    val normalizedUrl = coverUrl?.let(::normalizeCoverUrl)
    val cacheKey = contentCoverCacheKey(manga, normalizedUrl)
    val data = normalizedUrl?.takeUnless {
        isMissingLocalFileCover(it) || cacheKey?.let(ContentCoverFailureRegistry::isSuppressed) == true
    }
    val fallbackTvBoxSearchModel = manga.url
        .takeIf { it.startsWith("tvbox://item/") && coverUrl.isNullOrBlank() }
        ?.let { tvboxSearchCoverModel(manga) }
    if (data.isNullOrBlank()) {
        if (fallbackTvBoxSearchModel != null) {
            val fallbackCacheKey = contentCoverCacheKey(manga, "tvbox-search-cover:${manga.url}")
            Log.d(
                TAG,
                "Using deferred TVBox search cover: source=${manga.source.name} title=${manga.title} mangaUrl=${manga.url} model=$fallbackTvBoxSearchModel",
            )
            return ImageRequest.Builder(context)
                .data(fallbackTvBoxSearchModel)
                .memoryCacheKey(fallbackCacheKey)
                .diskCacheKey(fallbackCacheKey)
                .mangaExtra(manga)
                .crossfade(allowCrossfade)
                .build()
        }
        Log.w(
            TAG,
            "Cover request skipped: source=${manga.source.name} title=${manga.title} mangaUrl=${manga.url} inputCover=${coverUrl ?: "<null>"} normalized=${normalizedUrl ?: "<null>"} cacheKey=${cacheKey ?: "<null>"}",
        )
        return null
    }
    Log.d(
        TAG,
        "Cover request ready: source=${manga.source.name} title=${manga.title} mangaUrl=${manga.url} data=$data",
    )
    return ImageRequest.Builder(context)
        .data(data)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .mangaExtra(manga)
        .crossfade(allowCrossfade)
        .listener(
            onError = { _, result ->
                if (cacheKey != null && ContentCoverFailureRegistry.shouldSuppress(result.throwable)) {
                    ContentCoverFailureRegistry.mark(cacheKey)
                    onRecoverableFailure()
                }
            },
        )
        .build()
}

private fun normalizeCoverUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    else -> url
}

private fun isMissingLocalFileCover(url: String): Boolean {
    if (!url.startsWith("file://", ignoreCase = true)) {
        return false
    }
    val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return false
    return !File(path).isFile
}

private object ContentCoverFailureRegistry {

    private const val SUPPRESSION_WINDOW_MS = 10 * 60 * 1000L

    private val failures = ConcurrentHashMap<String, Long>()

    fun isSuppressed(key: String): Boolean {
        val failedAt = failures[key] ?: return false
        val isActive = System.currentTimeMillis() - failedAt < SUPPRESSION_WINDOW_MS
        if (!isActive) {
            failures.remove(key, failedAt)
        }
        return isActive
    }

    fun mark(key: String) {
        failures[key] = System.currentTimeMillis()
    }

    fun shouldSuppress(error: Throwable): Boolean = when (error) {
        is CloudFlareProtectedException -> true
        is HttpException -> error.response.code == 403
        else -> false
    }
}
