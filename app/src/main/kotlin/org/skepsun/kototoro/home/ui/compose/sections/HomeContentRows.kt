package org.skepsun.kototoro.home.ui.compose.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalSharedTransitionApi
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.list.ui.compose.contentListSharedElementKey
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardCornerBadges
import org.skepsun.kototoro.list.ui.compose.ContentCardCoverProgressIndicator
import org.skepsun.kototoro.list.ui.compose.KototoroContentCardGrid
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.list.ui.compose.rememberContentCardUiPrefs
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content

import org.skepsun.kototoro.home.ui.compose.HomeBadge
import org.skepsun.kototoro.home.ui.compose.rememberHomeCoverRequest
import org.skepsun.kototoro.home.ui.compose.toHeroCountLabel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Per-section display style of a home rail: which card layout to use, how
 * large the poster cards render and how many rows a list-mode page carries.
 * Each rail (history / updates / recommendations) carries its own instance,
 * configurable from the section header's settings button or the home more
 * menu's paged display options sheet.
 */
@Immutable
internal data class HomeRailStyle(
    val listMode: ListMode,
    val posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    val railRowsPerPage: Int = org.skepsun.kototoro.list.ui.compose.HOME_LIST_RAIL_ROWS_DEFAULT,
    /** Grid scale (0.5..1.5); also scales list-mode row covers so the grid slider has an effect there. */
    val gridScale: Float = 1f,
) {
    init {
        require(railRowsPerPage in org.skepsun.kototoro.list.ui.compose.HOME_LIST_RAIL_ROWS_MIN..
            org.skepsun.kototoro.list.ui.compose.HOME_LIST_RAIL_ROWS_MAX) {
            "railRowsPerPage out of range: $railRowsPerPage"
        }
    }
}

@Composable
internal fun HomeContentRowSection(
    title: String,
    sectionKey: String,
    iconRes: Int,
    items: List<HomeCoverDisplayItem>,
    count: Int,
    railStyle: HomeRailStyle,
    onItemClick: (Content, Rect?, String?) -> Unit,
    onMoreClick: () -> Unit,
    addTopSpacing: Boolean,
    onConfigureClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val listMode = railStyle.listMode
    val posterStyle = railStyle.posterStyle
    val rowState = rememberLazyListState()
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val scrollIntensity = rememberHorizontalRailScrollIntensity(rowState)
    val showMoreButton = true
    val railPages = remember(items, listMode, railStyle.railRowsPerPage) {
        when (listMode) {
            ListMode.GRID,
            ListMode.COMPACT_GRID -> emptyList()
            ListMode.LIST,
            ListMode.DETAILED_LIST -> items.chunked(railStyle.railRowsPerPage)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (addTopSpacing) 6.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HomeBadge(
                    text = count.toHeroCountLabel(),
                    iconRes = iconRes,
                )
            }
            if (onConfigureClick != null) {
                IconButton(
                    onClick = onConfigureClick,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.list_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (showMoreButton) {
                if (expressive) {
                    TextButton(
                        onClick = onMoreClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.more),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    TextButton(
                        onClick = onMoreClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.more),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        val railAnimationFactor = rememberRailAnimationFactor()
        when (listMode) {
            ListMode.GRID,
            ListMode.COMPACT_GRID -> {
                LazyRow(
                    state = rowState,
                    flingBehavior = rememberSnapFlingBehavior(rowState),
                    modifier = Modifier
                        .fillMaxWidth()
                        .extendHorizontalViewport(CompactTopBarHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(
                        // Keep the first item aligned with the section title while
                        // the viewport itself extends to the screen edge.
                        start = CompactTopBarHorizontalPadding,
                        end = 0.dp,
                    ),
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> "${item.sectionKey}:${item.stableKey}" },
                        contentType = { _, _ -> "home_content_card" },
                    ) { index, item ->
                        HorizontalRailAnimatedVisibility(
                            animationKey = "home_row_${title}_${item.stableKey}",
                            index = index,
                            listState = rowState,
                            scrollIntensity = scrollIntensity,
                            animationFactor = railAnimationFactor,
                            enableScrollLinkedAnimation = false,
                        ) { animatedModifier ->
                            HomeCoverRowItem(
                                item = item,
                                posterStyle = posterStyle,
                                listMode = listMode,
                                onClick = { coverBounds, sharedElementKey ->
                                    onItemClick(item.content, coverBounds, sharedElementKey)
                                },
                                modifier = animatedModifier,
                            )
                        }
                    }
                }
            }

            ListMode.LIST,
            ListMode.DETAILED_LIST -> {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val pageWidth = remember(maxWidth, listMode) {
                        calculateHomeListRailPageWidth(maxWidth, listMode)
                    }
                    val rowSpacing = 12.dp
                    val horizontalPadding = PaddingValues(
                        // Keep the snap anchor on the section content line. The end
                        // stays open so the final page can reach the screen edge.
                        start = CompactTopBarHorizontalPadding,
                        end = 0.dp,
                    )
                    LazyRow(
                        state = rowState,
                        flingBehavior = rememberSnapFlingBehavior(
                            lazyListState = rowState,
                            snapPosition = SnapPosition.Start,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .extendHorizontalViewport(CompactTopBarHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(rowSpacing),
                        contentPadding = horizontalPadding,
                    ) {
                        itemsIndexed(
                            items = railPages,
                            key = { index, page ->
                                val first = page.firstOrNull()
                                "${sectionKey}:${first?.stableKey ?: index}:page"
                            },
                            contentType = { _, _ -> "home_content_page" },
                        ) { index, pageItems ->
                            val pageKey = pageItems.firstOrNull()?.stableKey ?: index.toLong()
                            HorizontalRailAnimatedVisibility(
                                animationKey = "home_page_${title}_$pageKey",
                                index = index,
                                listState = rowState,
                                scrollIntensity = scrollIntensity,
                                animationFactor = railAnimationFactor,
                                enableScrollLinkedAnimation = false,
                            ) { animatedModifier ->
                                HomeListRailPage(
                                    items = pageItems,
                                    listMode = listMode,
                                    gridScale = railStyle.gridScale,
                                    onItemClick = onItemClick,
                                    modifier = animatedModifier.width(pageWidth),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.extendHorizontalViewport(extension: Dp): Modifier = layout {
    measurable, constraints ->
    val extensionPx = extension.roundToPx()
    val viewportWidth = if (constraints.hasBoundedWidth) {
        constraints.maxWidth
    } else {
        null
    }
    val expandedWidth = viewportWidth?.let { it + extensionPx * 2 }
    val placeable = measurable.measure(
        expandedWidth?.let {
            constraints.copy(minWidth = it, maxWidth = it)
        } ?: constraints,
    )
    layout(viewportWidth ?: placeable.width, placeable.height) {
        placeable.placeRelative(if (viewportWidth != null) -extensionPx else 0, 0)
    }
}

@Composable
private fun HomeListRailPage(
    items: List<HomeCoverDisplayItem>,
    listMode: ListMode,
    gridScale: Float,
    onItemClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            HomeListRailRowItem(
                item = item,
                listMode = listMode,
                gridScale = gridScale,
                onClick = onItemClick,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeListRailRowItem(
    item: HomeCoverDisplayItem,
    listMode: ListMode,
    gridScale: Float,
    onClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cardUiPrefs = rememberContentCardUiPrefs(
        remember(context.applicationContext) { AppSettings(context.applicationContext) },
    )
    val content = item.content
    // Grid size scales the row cover as well, so the grid-size slider has a
    // visible effect in list modes (width only; height keeps the 2:3 ratio).
    val baseWidth = when (listMode) {
        ListMode.LIST -> 52.dp
        ListMode.DETAILED_LIST -> 72.dp
        ListMode.GRID,
        ListMode.COMPACT_GRID -> 52.dp
    }
    val coverWidth = (baseWidth * gridScale).coerceIn(40.dp, 120.dp)
    val coverSize = HomeListRailCoverSize(width = coverWidth, height = coverWidth * 1.5f)
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val shouldCrossfadeCover = sharedTransitionScope == null || animatedVisibilityScope == null
    var coverBounds by remember(item.sectionKey, item.stableKey) { mutableStateOf<Rect?>(null) }
    val imageRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = shouldCrossfadeCover,
        memoryCacheVariant = "home_list_cover",
    )
    val badgeMetrics = remember(coverSize.width) { contentCardBadgeMetricsFor(coverSize.width) }
    val sharedElementKey = remember(item.sectionKey, item.stableKey, content.coverUrl, content.source.name) {
        contentCoverSharedKey(
            content.source.name,
            content.coverUrl.orEmpty(),
            instanceKey = "home_list_${item.sectionKey}_${item.stableKey}",
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(content, coverBounds, sharedElementKey) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(coverSize.width)
                .height(coverSize.height)
                .onGloballyPositioned { coordinates ->
                    coverBounds = coordinates.unclippedBoundsInWindow()
                }
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
                .clip(if (listMode == ListMode.DETAILED_LIST) ContentCoverShape else CompactContentCoverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = content.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { state ->
                        HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
                    },
                )
            }
            val badgeModel = remember(content, item.counter, item.progress) {
                ContentGridModel(
                    manga = content,
                    override = null,
                    subtitle = null,
                    counter = item.counter,
                    progress = item.progress,
                    isFavorite = false,
                    isSaved = false,
                )
            }
            val effectiveTopRightBadges = remember(cardUiPrefs.badgesTopRight, item.counter, badgeModel.scoreText) {
                buildSet {
                    addAll(cardUiPrefs.badgesTopRight)
                    if (item.counter > 0) {
                        add("counter")
                    }
                    if (!badgeModel.scoreText.isNullOrBlank()) {
                        add("score")
                    }
                }
            }
            ContentCardCornerBadges(
                badges = cardUiPrefs.badgesTopLeft,
                item = badgeModel,
                corner = Alignment.TopStart,
                cardRadius = 8.dp,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopStart),
            )
            ContentCardCornerBadges(
                badges = effectiveTopRightBadges,
                item = badgeModel,
                corner = Alignment.TopEnd,
                cardRadius = 8.dp,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            ContentCardCornerBadges(
                badges = cardUiPrefs.badgesBottomLeft,
                item = badgeModel,
                corner = Alignment.BottomStart,
                cardRadius = 8.dp,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            ContentCardCornerBadges(
                badges = cardUiPrefs.badgesBottomRight,
                item = badgeModel,
                corner = Alignment.BottomEnd,
                cardRadius = 8.dp,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
            ContentCardCoverProgressIndicator(
                progress = item.progress,
                bottomRightBadges = cardUiPrefs.badgesBottomRight,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (listMode == ListMode.DETAILED_LIST) 4.dp else 3.dp),
        ) {
            Text(
                text = content.title,
                style = if (listMode == ListMode.DETAILED_LIST) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (listMode == ListMode.DETAILED_LIST) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                item.supportingText != null -> {
                    Text(
                        text = item.supportingText.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (listMode == ListMode.DETAILED_LIST) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                listMode == ListMode.DETAILED_LIST -> {
                    val detailText = remember(content.altTitles, content.tags) {
                        content.altTitles.firstOrNull()?.takeIf { it.isNotBlank() }
                            ?: content.tags.take(3).joinToString(" · ") { it.title }.takeIf { it.isNotBlank() }
                    }
                    detailText?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = rememberResolvedSourceTitle(content.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeCoverRowItem(
    item: HomeCoverDisplayItem,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    listMode: ListMode,
    onClick: (Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = item.content
    val gridInstanceKey = "home_row_${item.sectionKey}_${item.stableKey}"
    val model = remember(content, item.supportingText, item.counter, item.progress) {
        ContentGridModel(
            manga = content,
            override = null,
            subtitle = item.supportingText?.text,
            counter = item.counter,
            progress = item.progress,
            isFavorite = false,
            isSaved = false,
        )
    }
    // The grid card registers its shared element with
    // contentListSharedElementKey(model, gridInstanceKey); the click key must be
    // derived the SAME way or the details cover will never match the card and the
    // hero transition silently does not run (a contentCoverSharedKey here produced
    // cover|<source>|<url>|... against the card's cover|list-item|<id>|...).
    val sharedElementKey = remember(model.id, gridInstanceKey) {
        contentListSharedElementKey(model, gridInstanceKey)
    }

    KototoroContentCardGrid(
        item = model,
        sharedElementInstanceKey = gridInstanceKey,
        cardStyle = posterStyle,
        compactOverlay = listMode == ListMode.COMPACT_GRID,
        // Tighter than the global grid default: rails sit on a shared screen
        // where every vertical dp of padding compounds across sections.
        cellContentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        onClick = { coverBounds -> onClick(coverBounds, sharedElementKey) },
        onLongClick = {},
        modifier = modifier.width(posterStyle.itemWidth),
    )
}

internal fun itemNewChaptersText(label: String, count: Int): String = "$label $count"

private fun calculateHomeListRailPageWidth(maxWidth: Dp, listMode: ListMode): Dp {
    val referenceWidth = maxWidth.coerceAtMost(HOME_LIST_RAIL_REFERENCE_VIEWPORT_WIDTH)
    val targetWidth = when (listMode) {
        ListMode.DETAILED_LIST -> referenceWidth * HOME_DETAILED_RAIL_PAGE_WIDTH_RATIO
        ListMode.LIST -> referenceWidth * HOME_LIST_RAIL_PAGE_WIDTH_RATIO
        ListMode.GRID,
        ListMode.COMPACT_GRID -> referenceWidth * HOME_LIST_RAIL_PAGE_WIDTH_RATIO
    }
    val maxPageWidth = if (listMode == ListMode.DETAILED_LIST) {
        HOME_DETAILED_RAIL_PAGE_MAX_WIDTH
    } else {
        HOME_LIST_RAIL_PAGE_MAX_WIDTH
    }
    return targetWidth.coerceAtMost(maxPageWidth).coerceAtLeast(HOME_LIST_RAIL_PAGE_MIN_WIDTH)
}

@Immutable
internal data class HomeCoverDisplayItem(
    val content: Content,
    val sectionKey: String,
    val stableKey: Long,
    val counter: Int = 0,
    val progress: ReadingProgress? = null,
    val supportingText: HomeCoverSupportingText? = null,
)

@Immutable
private data class HomeListRailCoverSize(
    val width: Dp,
    val height: Dp,
)

@Immutable
internal data class HomeCoverSupportingText(
    val text: String,
) {
    companion object {
        fun Text(text: String) = HomeCoverSupportingText(text)
    }
}

private val HOME_LIST_RAIL_PAGE_MIN_WIDTH = 280.dp
private val HOME_LIST_RAIL_PAGE_MAX_WIDTH = 320.dp
private val HOME_DETAILED_RAIL_PAGE_MAX_WIDTH = 368.dp
private val HOME_LIST_RAIL_REFERENCE_VIEWPORT_WIDTH = 384.dp
private const val HOME_LIST_RAIL_PAGE_WIDTH_RATIO = 0.74f
private const val HOME_DETAILED_RAIL_PAGE_WIDTH_RATIO = 0.84f

