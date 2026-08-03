package org.skepsun.kototoro.home.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.animation.ExperimentalSharedTransitionApi
import coil3.Image as CoilImage
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import dagger.hilt.android.EntryPointAccessors
import java.util.Locale
import kotlin.math.absoluteValue
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.ui.compose.HeroAutoAdvanceEffect
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.compose.rememberDrawablePainter
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.image.tvboxSearchCoverModel
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.details.ui.compose.AnimatedPanoramaBackdrop
import org.skepsun.kototoro.details.ui.compose.PanoramaBackdropPrefs
import org.skepsun.kototoro.details.ui.compose.rememberPanoramaBackdropPrefs
import org.skepsun.kototoro.home.ui.HOME_HERO_HISTORY_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_RECOMMENDATIONS_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_TOTAL_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_UPDATES_LIMIT
import org.skepsun.kototoro.home.ui.HomeRecentItem
import org.skepsun.kototoro.home.ui.HomeRecommendationItem
import org.skepsun.kototoro.home.ui.HomeSummaryState
import org.skepsun.kototoro.home.ui.HomeUpdateItem
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardCornerBadges
import org.skepsun.kototoro.list.ui.compose.ContentCardCoverProgressIndicator
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.KototoroContentCardGrid
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.list.ui.compose.rememberContentCardUiPrefs
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content

@Immutable
private data class HomeScreenPrefs(
    val gridScale: Float,
    val listMode: ListMode,
)

@Stable
data class HomeScreenActions(
    val onSettingsClick: () -> Unit,
    val onReaderSettingsClick: () -> Unit,
    val onViewAllRecentClick: () -> Unit,
    val onViewAllUpdatesClick: () -> Unit,
    val onViewAllRecommendationsClick: () -> Unit,
    val onRecentSearchClick: (String) -> Unit,
    val onSetupWizardClick: () -> Unit,
    val onManageSourcesClick: () -> Unit,
    val onLibraryOpenClick: () -> Unit,
    val onBookmarksClick: () -> Unit,
    val onLocalClick: () -> Unit,
    val onDownloadsClick: () -> Unit,
    val onRandomClick: () -> Unit,
    val onAutoTranslateClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: HomeSummaryState,
    onContentClick: (Content, Rect?, String?) -> Unit,
    actions: HomeScreenActions,
    isRandomLoading: Boolean,
    modifier: Modifier = Modifier,
    autoAdvanceHero: Boolean = true,
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_LIST_MODE_HOME,
    ) {
        HomeScreenPrefs(
            gridScale = gridSize / 100f,
            listMode = homeListMode,
        )
    }
    val gridScale = screenPrefs.gridScale
    val listMode = screenPrefs.listMode
    val posterStyle = remember(gridScale) { compactPosterCardStyle(gridScale) }
    val panoramaPrefs = rememberPanoramaBackdropPrefs(settings)
    val homeHeroPanoramaPrefs = remember(panoramaPrefs) {
        panoramaPrefs.copy(isAnimationEnabled = false)
    }
    val recentSearches = remember(state.recentSearches) { state.recentSearches.map { it.query } }
    val heroEntries = remember(
        state.resumeState.content,
        state.resumeState.groupKey,
        state.resumeState.progressPercent,
        state.recentHistoryItems,
        state.recentUpdates,
        state.recommendations,
    ) {
        buildHomeHeroEntries(
            resumeContent = state.resumeState.content,
            resumeGroupKey = state.resumeState.groupKey,
            resumeProgressPercent = state.resumeState.progressPercent,
            historyItems = state.recentHistoryItems,
            updateItems = state.recentUpdates,
            recommendationItems = state.recommendations,
        )
    }
    val quickActions = listOf(
            HomeQuickAction(stringResource(R.string.home_quick_action_wizard), R.drawable.ic_welcome, actions.onSetupWizardClick),
            HomeQuickAction(stringResource(R.string.favourites), R.drawable.ic_heart, actions.onLibraryOpenClick),
            HomeQuickAction(stringResource(R.string.bookmarks), R.drawable.ic_bookmark, actions.onBookmarksClick),
            HomeQuickAction(stringResource(R.string.local_storage), R.drawable.ic_storage, actions.onLocalClick),
            HomeQuickAction(stringResource(R.string.downloads), R.drawable.ic_download, actions.onDownloadsClick),
            HomeQuickAction(stringResource(R.string.random), R.drawable.ic_dice, actions.onRandomClick, !isRandomLoading),
            HomeQuickAction(stringResource(R.string.home_quick_action_extensions), R.drawable.ic_extension, actions.onManageSourcesClick),
            HomeQuickAction(stringResource(R.string.translation_settings), R.drawable.ic_language, actions.onAutoTranslateClick),
            HomeQuickAction(stringResource(R.string.reader_settings), R.drawable.ic_read, actions.onReaderSettingsClick),
            HomeQuickAction(stringResource(R.string.settings), R.drawable.ic_settings, actions.onSettingsClick),
    )
    val topInset = contentPadding.calculateTopPadding()
    val scrollTopInset = if (heroEntries.isEmpty()) {
        maxOf(topInset, systemBarsPadding.calculateTopPadding()) + 8.dp
    } else {
        0.dp
    }
    val estimatedHeroPx = with(density) { (340.dp + topInset).roundToPx() }
    var heroPx by rememberSaveable { mutableIntStateOf(estimatedHeroPx) }
    val heroHeightDp by remember(heroPx, density) {
        derivedStateOf { with(density) { heroPx.toDp() } }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .padding(
                    start = systemBarsPadding.calculateLeftPadding(layoutDirection) + CompactTopBarHorizontalPadding,
                    end = systemBarsPadding.calculateRightPadding(layoutDirection) + CompactTopBarHorizontalPadding,
                ),
            contentPadding = PaddingValues(
                top = scrollTopInset,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasHighlights = heroEntries.isNotEmpty() ||
                state.recentHistoryItems.isNotEmpty() ||
                state.recentUpdates.isNotEmpty() ||
                state.recommendations.isNotEmpty() ||
                recentSearches.isNotEmpty()
            if (hasHighlights) {
                if (heroEntries.isNotEmpty()) {
                    item(key = "home_hero_spacer") {
                        Spacer(modifier = Modifier.height(heroHeightDp))
                    }
                }
                item(key = "home_highlights") {
                    HomeHighlightsSections(
                        historyItems = state.recentHistoryItems,
                        recentHistoryCount = state.recentHistoryCount,
                        updateItems = state.recentUpdates,
                        unreadUpdatesCount = state.unreadUpdatesCount,
                        recommendationItems = state.recommendations,
                        recommendationsCount = state.recommendationsCount,
                        recentSearches = recentSearches,
                        posterStyle = posterStyle,
                        listMode = listMode,
                        onItemClick = onContentClick,
                        onViewAllRecentClick = actions.onViewAllRecentClick,
                        onViewAllUpdatesClick = actions.onViewAllUpdatesClick,
                        onViewAllRecommendationsClick = actions.onViewAllRecommendationsClick,
                        onRecentSearchClick = actions.onRecentSearchClick,
                    )
                }
            }
            if (!hasHighlights && !state.isInitialized) {
                item(key = "home_loading_skeleton") {
                    HomeLoadingSkeleton(posterStyle = posterStyle)
                }
            }

            item(key = "home_quick_actions") {
                QuickActionsSection(actions = quickActions)
            }
        }

        if (heroEntries.isNotEmpty()) {
            HomeHeroSection(
                entries = heroEntries,
                panoramaPrefs = homeHeroPanoramaPrefs,
                onClick = onContentClick,
                topContentInset = topInset + 8.dp,
                autoAdvance = autoAdvanceHero,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        val scrollOffset = if (listState.firstVisibleItemIndex == 0) {
                            listState.firstVisibleItemScrollOffset.toFloat()
                        } else {
                            heroPx.toFloat()
                        }
                        translationY = -scrollOffset.coerceIn(0f, heroPx.toFloat())
                    }
                    .onGloballyPositioned { coordinates ->
                        val newHeight = coordinates.size.height
                        if (heroPx != newHeight) heroPx = newHeight
                    },
            )
        }
    }
}


@Composable
private fun HomeHighlightsSections(
    historyItems: List<HomeRecentItem>,
    recentHistoryCount: Int,
    updateItems: List<HomeUpdateItem>,
    unreadUpdatesCount: Int,
    recommendationItems: List<HomeRecommendationItem>,
    recommendationsCount: Int,
    recentSearches: List<String>,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    listMode: ListMode,
    onItemClick: (Content, Rect?, String?) -> Unit,
    onViewAllRecentClick: () -> Unit,
    onViewAllUpdatesClick: () -> Unit,
    onViewAllRecommendationsClick: () -> Unit,
    onRecentSearchClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val sectionShape = RoundedCornerShape(
        when {
            expressive -> 28.dp
            isIosStyle -> 8.dp
            else -> 20.dp
        },
    )
    val sectionColor = if (expressive || isIosStyle) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)
    }
    val newChaptersLabel = stringResource(R.string.new_chapters)
    val historyDisplayItems = remember(historyItems) {
        historyItems.take(HOME_CONTENT_RAIL_PREVIEW_LIMIT).map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recent_history",
                stableKey = it.groupKey,
                counter = it.counter,
                progress = it.progress,
            )
        }
    }
    val updateDisplayItems = remember(updateItems, newChaptersLabel) {
        updateItems.take(HOME_CONTENT_RAIL_PREVIEW_LIMIT).map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recent_updates",
                stableKey = it.groupKey,
                counter = it.counter,
                progress = it.progress,
                supportingText = if (it.newChapters > 0) {
                    HomeCoverSupportingText.Text(
                        itemNewChaptersText(newChaptersLabel, it.newChapters),
                    )
                } else {
                    null
                },
            )
        }
    }
    val recommendationDisplayItems = remember(recommendationItems) {
        recommendationItems.take(HOME_CONTENT_RAIL_PREVIEW_LIMIT).map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recommendations",
                stableKey = it.groupKey,
                counter = it.counter,
                progress = it.progress,
            )
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (historyItems.isNotEmpty()) {
            HomeHighlightSectionContainer(
                expressive = expressive,
                isIosStyle = isIosStyle,
                shape = sectionShape,
                color = sectionColor,
            ) {
                HomeContentRowSection(
                    title = stringResource(R.string.recent_history),
                    sectionKey = "recent_history",
                    iconRes = R.drawable.ic_history,
                    items = historyDisplayItems,
                    count = recentHistoryCount,
                    posterStyle = posterStyle,
                    listMode = listMode,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllRecentClick,
                    addTopSpacing = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (updateItems.isNotEmpty()) {
            HomeHighlightSectionContainer(
                expressive = expressive,
                isIosStyle = isIosStyle,
                shape = sectionShape,
                color = sectionColor,
            ) {
                HomeContentRowSection(
                    title = stringResource(R.string.home_recent_updates),
                    sectionKey = "recent_updates",
                    iconRes = R.drawable.ic_updated,
                    items = updateDisplayItems,
                    count = unreadUpdatesCount,
                    posterStyle = posterStyle,
                    listMode = listMode,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllUpdatesClick,
                    addTopSpacing = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (recommendationItems.isNotEmpty()) {
            HomeHighlightSectionContainer(
                expressive = expressive,
                isIosStyle = isIosStyle,
                shape = sectionShape,
                color = sectionColor,
            ) {
                HomeContentRowSection(
                    title = stringResource(R.string.suggestions),
                    sectionKey = "recommendations",
                    iconRes = R.drawable.ic_suggestion,
                    items = recommendationDisplayItems,
                    count = recommendationsCount,
                    posterStyle = posterStyle,
                    listMode = listMode,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllRecommendationsClick,
                    addTopSpacing = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (recentSearches.isNotEmpty()) {
            HomeRecentSearchSection(
                queries = recentSearches,
                onQueryClick = onRecentSearchClick,
            )
        }
    }
}

@Composable
private fun HomeHighlightSectionContainer(
    expressive: Boolean,
    isIosStyle: Boolean,
    shape: RoundedCornerShape,
    color: Color,
    content: @Composable () -> Unit,
) {
    if (expressive || isIosStyle) {
        content()
    } else {
        Surface(
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            content = content,
        )
    }
}

@Composable
private fun HomeHeroSection(
    entries: List<HomeHeroEntry>,
    panoramaPrefs: PanoramaBackdropPrefs,
    onClick: (Content, Rect?, String?) -> Unit,
    topContentInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = true,
) {
    if (entries.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { entries.size })
    val selectedIndex by remember(entries, pagerState) {
        derivedStateOf { pagerState.currentPage.coerceIn(0, entries.lastIndex) }
    }

    HeroAutoAdvanceEffect(
        pagerState = pagerState,
        pageCount = entries.size,
        intervalMillis = 5200L,
        enabled = autoAdvance,
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topContentInset),
    ) {
        val edgePadding = CompactTopBarHorizontalPadding
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val portraitViewportWidth = if (isLandscape) {
            LocalConfiguration.current.screenHeightDp.dp
        } else {
            maxWidth
        }
        val cardWidth = (portraitViewportWidth - edgePadding * 2)
            .coerceAtLeast(0.dp)
            .coerceAtMost(HOME_HERO_PAGER_MAX_WIDTH)
        val pageSpacing = 6.dp
        val contentPadding = if (isLandscape) {
            PaddingValues(start = edgePadding, end = 0.dp)
        } else {
            val horizontal = ((maxWidth - cardWidth) / 2).coerceAtLeast(edgePadding)
            PaddingValues(horizontal = horizontal)
        }
        val viewportWidth = maxWidth
        val density = LocalDensity.current
        val contentPadPx = with(density) { contentPadding.calculateLeftPadding(LocalLayoutDirection.current).toPx() }
        val stepPx = with(density) { (cardWidth + pageSpacing).toPx() }
        val pagerWidthPx = with(density) { viewportWidth.toPx() }
        val cardWidthPx = with(density) { cardWidth.toPx() }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(cardWidth),
                pageSpacing = pageSpacing,
                beyondViewportPageCount = 1,
                contentPadding = contentPadding,
                key = { page ->
                    entries.getOrNull(page)?.let { entry ->
                        "home_hero_${entry.kind.name}_${entry.groupKey}_${entry.content.id}"
                    } ?: "home_hero_pending_$page"
                },
                modifier = Modifier.width(viewportWidth),
            ) { page ->
                entries.getOrNull(page)?.let { entry ->
                    HomeHeroCard(
                        entry = entry,
                        bottomInset = 10.dp,
                        posterWidth = 82.dp,
                        posterHeight = 114.dp,
                        panoramaPrefs = panoramaPrefs,
                        onClick = onClick,
                        modifier = Modifier
                            .zIndex(if (page == selectedIndex) 1f else 0f)
                            .graphicsLayer {
                                val rawOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                val signedOffset = rawOffset.coerceIn(-1f, 1f)
                                val visualLeft = contentPadPx - rawOffset * stepPx
                                val visualRight = visualLeft + cardWidthPx
                                val focus = when {
                                    visualRight <= 0f || visualLeft >= pagerWidthPx -> 0f
                                    visualLeft >= 0f && visualRight <= pagerWidthPx -> 1f
                                    visualLeft < 0f -> (visualRight / cardWidthPx).coerceIn(0f, 1f)
                                    else -> ((pagerWidthPx - visualLeft) / cardWidthPx).coerceIn(0f, 1f)
                                }
                                val hOrigin = when {
                                    signedOffset < -0.02f -> 0f
                                    signedOffset > 0.02f -> 1f
                                    else -> 0.5f
                                }
                                scaleX = 0.9f + (0.1f * focus)
                                scaleY = 0.9f + (0.1f * focus)
                                alpha = 0.64f + (0.36f * focus)
                                transformOrigin = TransformOrigin(hOrigin, 0.5f)
                            },
                    )
                }
            }
        }

        if (entries.size > 1) {
            val currentEntry = entries[selectedIndex]
            HeroPagerIndicator(
                pageCount = entries.size,
                currentPage = selectedIndex,
                pageCounter = "${selectedIndex + 1} / ${entries.size}",
                counterColor = Color.White.copy(alpha = 0.78f),
                trailingIcon = {
                    Icon(
                        painter = painterResource(currentEntry.kind.iconRes),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 12.dp),
            )
        }

    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeHeroCard(
    entry: HomeHeroEntry,
    bottomInset: Dp,
    posterWidth: Dp,
    posterHeight: Dp,
    panoramaPrefs: PanoramaBackdropPrefs,
    onClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val content = entry.content
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val backdropRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = true,
        memoryCacheVariant = "home_hero_backdrop",
    )
    val coverRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = true,
        memoryCacheVariant = "home_hero_cover",
    )
    var coverBounds by remember(entry.kind, entry.groupKey) { mutableStateOf<Rect?>(null) }
    val sharedElementKey = remember(entry.kind, entry.groupKey, content.coverUrl, content.source.name) {
        contentCoverSharedKey(
            sourceName = content.source.name,
            url = content.coverUrl.orEmpty(),
            instanceKey = "home_hero_${entry.kind.name.lowercase(Locale.ROOT)}_${entry.groupKey}",
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(208.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick(content, coverBounds, sharedElementKey) },
    ) {
        if (panoramaPrefs.isEnabled && backdropRequest != null) {
            AnimatedPanoramaBackdrop(
                prefs = panoramaPrefs,
                model = backdropRequest,
                placeholderMemoryCacheKey = coverRequest?.memoryCacheKey,
                snapshotKey = sharedElementKey,
                contentAlpha = 0.94f,
                backgroundColor = MaterialTheme.colorScheme.surface,
            )
        } else if (backdropRequest != null) {
            AsyncImage(
                model = backdropRequest,
                contentDescription = content.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.22f),
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.48f),
                            ),
                        ),
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.18f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.14f),
                            ),
                        ),
                    )
                },
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 14.dp,
                    top = 12.dp,
                    end = 14.dp,
                    bottom = bottomInset,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = posterWidth, height = posterHeight)
                    .onGloballyPositioned { coordinates ->
                        coverBounds = coordinates.unclippedBoundsInWindow()
                    }
                    .clip(ContentCoverShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)),
            ) {
                if (coverRequest != null) {
                    HomeHeroCoverImage(
                        request = coverRequest,
                        cacheKey = coverRequest.memoryCacheKey,
                        snapshotKey = sharedElementKey,
                        contentDescription = content.title,
                        modifier = Modifier.fillMaxSize(),
                        onSuccess = { result ->
                            HeroCoverSnapshotStore.put(sharedElementKey, result.image)
                        },
                    )
                }
                if (content.isNsfw()) {
                    ContentCardNsfwBadge(
                        metrics = contentCardBadgeMetricsFor(posterWidth),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HomeBadge(
                    text = stringResource(entry.kind.labelRes),
                    iconRes = entry.kind.iconRes,
                )
                Text(
                    text = content.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rememberResolvedSourceTitle(content.source),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.supportingText()?.let { supportingText ->
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

    }
}

@Composable
private fun HomeHeroCoverImage(
    request: ImageRequest,
    cacheKey: String?,
    snapshotKey: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onSuccess: (SuccessResult) -> Unit,
    onError: (AsyncImagePainter.State.Error) -> Unit = {},
) {
    val context = LocalContext.current
    val imageLoader = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    val cachedImage = remember(imageLoader, cacheKey, snapshotKey) {
        cacheKey?.let { key ->
            imageLoader.memoryCache?.get(MemoryCache.Key(key))?.image
        } ?: HeroCoverSnapshotStore.get(snapshotKey)
    }
    var stableImage by remember(cacheKey, snapshotKey) { mutableStateOf<CoilImage?>(cachedImage) }
    val stablePainter = rememberDrawablePainter(stableImage?.asDrawable(context.resources))

    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        placeholder = stablePainter,
        error = stablePainter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        onSuccess = { result ->
            stableImage = result.result.image
            onSuccess(result.result)
        },
        onError = onError,
    )
}

@Composable
private fun HomeRecentSearchSection(
    queries: List<String>,
    onQueryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val chipShape = RoundedCornerShape(if (expressive) 16.dp else 8.dp)
    val chipColors = AssistChipDefaults.assistChipColors(
        containerColor = if (expressive) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        },
        labelColor = MaterialTheme.colorScheme.onSurface,
        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.home_recent_searches),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HomeBadge(
                text = queries.size.toHeroCountLabel(),
                iconRes = R.drawable.ic_history,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            queries.forEach { query ->
                AssistChip(
                    onClick = { onQueryClick(query) },
                    modifier = Modifier.height(32.dp),
                    shape = chipShape,
                    colors = chipColors,
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    label = {
                        Text(
                            text = query,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeContentRowSection(
    title: String,
    sectionKey: String,
    iconRes: Int,
    items: List<HomeCoverDisplayItem>,
    count: Int,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    listMode: ListMode,
    onItemClick: (Content, Rect?, String?) -> Unit,
    onMoreClick: () -> Unit,
    addTopSpacing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val rowState = rememberLazyListState()
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val scrollIntensity = rememberHorizontalRailScrollIntensity(rowState)
    val showMoreButton = true
    val railPages = remember(items, listMode) {
        when (listMode) {
            ListMode.GRID,
            ListMode.COMPACT_GRID -> emptyList()
            ListMode.LIST,
            ListMode.DETAILED_LIST -> items.chunked(HOME_LIST_RAIL_PAGE_SIZE)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (addTopSpacing) 6.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
    onItemClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            HomeListRailRowItem(
                item = item,
                listMode = listMode,
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
    onClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cardUiPrefs = rememberContentCardUiPrefs(
        remember(context.applicationContext) { AppSettings(context.applicationContext) },
    )
    val content = item.content
    val coverSize = when (listMode) {
        ListMode.LIST -> HomeListRailCoverSize(52.dp, 78.dp)
        ListMode.DETAILED_LIST -> HomeListRailCoverSize(72.dp, 108.dp)
        ListMode.GRID,
        ListMode.COMPACT_GRID -> HomeListRailCoverSize(52.dp, 78.dp)
    }
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
    val sharedElementKey = remember(item.sectionKey, item.stableKey, content.coverUrl, content.source.name) {
        contentCoverSharedKey(
            content,
            content.coverUrl,
            instanceKey = "home_row_${item.sectionKey}_${item.stableKey}",
        )
    }
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

    KototoroContentCardGrid(
        item = model,
        sharedElementInstanceKey = "home_row_${item.sectionKey}_${item.stableKey}",
        cardStyle = posterStyle,
        compactOverlay = listMode == ListMode.COMPACT_GRID,
        onClick = { coverBounds -> onClick(coverBounds, sharedElementKey) },
        onLongClick = {},
        modifier = modifier.width(posterStyle.itemWidth),
    )
}

private fun itemNewChaptersText(label: String, count: Int): String = "$label $count"

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
private data class HomeCoverDisplayItem(
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
private data class HomeCoverSupportingText(
    val text: String,
) {
    companion object {
        fun Text(text: String) = HomeCoverSupportingText(text)
    }
}

private const val HOME_LIST_RAIL_PAGE_SIZE = 3
private const val HOME_CONTENT_RAIL_PREVIEW_LIMIT = 24
private val HOME_HERO_PAGER_MAX_WIDTH = 1240.dp
private val HOME_LIST_RAIL_PAGE_MIN_WIDTH = 280.dp
private val HOME_LIST_RAIL_PAGE_MAX_WIDTH = 320.dp
private val HOME_DETAILED_RAIL_PAGE_MAX_WIDTH = 368.dp
private val HOME_LIST_RAIL_REFERENCE_VIEWPORT_WIDTH = 384.dp
private const val HOME_LIST_RAIL_PAGE_WIDTH_RATIO = 0.74f
private const val HOME_DETAILED_RAIL_PAGE_WIDTH_RATIO = 0.84f

@Composable
private fun QuickActionsSection(
    actions: List<HomeQuickAction>,
    modifier: Modifier = Modifier,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.quick_access),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        androidx.compose.foundation.layout.BoxWithConstraints {
            val compact = maxWidth < 680.dp
            val itemsPerRow = when {
                maxWidth >= 800.dp -> 4
                maxWidth >= 620.dp -> 3
                isIosStyle -> 3
                maxWidth >= 360.dp -> 4
                else -> 3
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = if (isIosStyle) {
                    Arrangement.spacedBy(7.dp, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(8.dp)
                },
                maxItemsInEachRow = itemsPerRow,
            ) {
                actions.forEach { action ->
                    QuickAccessButton(
                        action = action,
                        compact = compact,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                }
            }
        }
    }
}

private data class HomeQuickAction(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun QuickAccessButton(
    action: HomeQuickAction,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Surface(
        modifier = modifier.then(if (isIosStyle) Modifier.heightIn(min = 72.dp) else Modifier),
        shape = RoundedCornerShape(if (expressive) 24.dp else 18.dp),
        color = if (isIosStyle) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (expressive) 0.dp else 1.dp,
        border = when {
            isIosStyle -> BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
            expressive -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
            else -> null
        },
    ) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isIosStyle) Modifier.heightIn(min = 72.dp) else Modifier)
                    .clickable(enabled = action.enabled, onClick = action.onClick)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = if (isIosStyle) {
                    Arrangement.spacedBy(7.dp, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(8.dp)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HomeQuickActionIcon(
                    iconRes = action.iconRes,
                    enabled = action.enabled,
                    modifier = Modifier.size(if (expressive) 22.dp else 18.dp),
                )
                Text(
                    text = action.label,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isIosStyle) Modifier.heightIn(min = 72.dp) else Modifier)
                    .clickable(enabled = action.enabled, onClick = action.onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeQuickActionIcon(
                    iconRes = action.iconRes,
                    enabled = action.enabled,
                    modifier = Modifier.size(if (expressive) 24.dp else 20.dp),
                )
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeQuickActionIcon(
    iconRes: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val tint = if (enabled) {
        if (isIosStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
private fun HomeBadge(
    text: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeLoadingSkeleton(
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp),
        )
        repeat(2) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeSkeletonBlock(
                        modifier = Modifier
                            .width(124.dp)
                            .height(16.dp),
                    )
                    HomeSkeletonBlock(
                        modifier = Modifier
                            .width(52.dp)
                            .height(14.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(3) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HomeSkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(posterStyle.posterHeight),
                            )
                            HomeSkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(12.dp),
                            )
                            HomeSkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth(0.64f)
                                    .height(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSkeletonBlock(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)),
    )
}

@Composable
private fun HomeStatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Int.toHeroCountLabel(): String = when {
    this >= 10_000 -> "${this / 1000}k+"
    this >= 1_000 -> "${this / 1000}k"
    else -> toString()
}

private enum class HomeHeroKind(val labelRes: Int, val iconRes: Int) {
    RESUME(R.string.home_resume_title, R.drawable.ic_read),
    HISTORY(R.string.recent_history, R.drawable.ic_history),
    UPDATE(R.string.home_recent_updates, R.drawable.ic_updated),
    RECOMMENDATION(R.string.suggestions, R.drawable.ic_suggestion),
}

private data class HomeHeroEntry(
    val kind: HomeHeroKind,
    val content: Content,
    val groupKey: Long,
    val progressPercent: Int? = null,
    val newChapters: Int = 0,
)

private val HomeHeroEntry.sharedElementKey: String
    get() = contentCoverSharedKey(
        sourceName = content.source.name,
        url = content.coverUrl.orEmpty(),
        instanceKey = "home_hero_${kind.name.lowercase(Locale.ROOT)}_${content.id}",
    )

@Composable
private fun HomeHeroEntry.supportingText(): String? = when (kind) {
    HomeHeroKind.RESUME -> null
    HomeHeroKind.UPDATE -> newChapters
        .takeIf { it > 0 }
        ?.let { value ->
            stringResource(
                R.string.new_chapters_pattern,
                stringResource(R.string.new_chapters),
                value,
            )
        }
    HomeHeroKind.HISTORY,
    HomeHeroKind.RECOMMENDATION -> null
}

private fun buildHomeHeroEntries(
    resumeContent: Content?,
    resumeGroupKey: Long?,
    resumeProgressPercent: Int?,
    historyItems: List<org.skepsun.kototoro.home.ui.HomeRecentItem>,
    updateItems: List<org.skepsun.kototoro.home.ui.HomeUpdateItem>,
    recommendationItems: List<org.skepsun.kototoro.home.ui.HomeRecommendationItem>,
): List<HomeHeroEntry> {
    val entries = ArrayList<HomeHeroEntry>(HOME_HERO_TOTAL_LIMIT)

    fun addEntry(entry: HomeHeroEntry) {
        if (entries.size >= HOME_HERO_TOTAL_LIMIT) return
        entries += entry
    }

    resumeContent?.let { content ->
        addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.RESUME,
                    content = content,
                    groupKey = resumeGroupKey ?: content.id,
                    progressPercent = resumeProgressPercent,
                ),
            )
        }

    historyItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_HISTORY_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.HISTORY,
                    content = item.content,
                    groupKey = item.groupKey,
                ),
            )
        }

    updateItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_UPDATES_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.UPDATE,
                    content = item.content,
                    groupKey = item.groupKey,
                    newChapters = item.newChapters,
                ),
            )
        }

    recommendationItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_RECOMMENDATIONS_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.RECOMMENDATION,
                    content = item.content,
                    groupKey = item.groupKey,
                ),
            )
        }

    return entries
}

@Composable
private fun rememberHomeCoverRequest(
    context: android.content.Context,
    content: Content,
    allowCrossfade: Boolean,
    memoryCacheVariant: String,
): ImageRequest? {
    val primaryCoverUrl = content.coverUrl?.takeIfUsableImageUri()
    val fallbackCoverUrl = content.largeCoverUrl?.takeIfUsableImageUri()
    return remember(
        context,
        content.id,
        content.source.name,
        content.url,
        content.publicUrl,
        primaryCoverUrl,
        fallbackCoverUrl,
        allowCrossfade,
        memoryCacheVariant,
    ) {
        val resolvedCoverUrl = primaryCoverUrl ?: fallbackCoverUrl
        if (resolvedCoverUrl != null) {
            val cacheKey = contentCoverCacheKey(content, resolvedCoverUrl)
            return@remember ImageRequest.Builder(context)
                .data(resolvedCoverUrl)
                .memoryCacheKey(cacheKey.withMemoryCacheVariant(memoryCacheVariant))
                .diskCacheKey(cacheKey)
                .crossfade(allowCrossfade)
                .apply { mangaExtra(content) }
                .build()
        }
        if (content.url.startsWith("tvbox://item/")) {
            val fallbackCacheKey = contentCoverCacheKey(content, "tvbox-search-cover:${content.url}")
            return@remember ImageRequest.Builder(context)
                .data(tvboxSearchCoverModel(content))
                .memoryCacheKey(fallbackCacheKey.withMemoryCacheVariant(memoryCacheVariant))
                .diskCacheKey(fallbackCacheKey)
                .crossfade(allowCrossfade)
                .mangaExtra(content)
                .build()
        }
        null
    }
}

private fun String?.withMemoryCacheVariant(variant: String): String? {
    return this?.let { "$it#$variant" }
}
