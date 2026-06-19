package org.skepsun.kototoro.home.ui.compose

import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.animation.ExperimentalSharedTransitionApi
import coil3.compose.AsyncImage
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import java.util.Locale
import kotlin.math.absoluteValue
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.HeroAutoAdvanceEffect
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.image.tvboxSearchCoverModel
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.details.ui.compose.AnimatedPanoramaBackdrop
import org.skepsun.kototoro.details.ui.compose.PanoramaBackdropPrefs
import org.skepsun.kototoro.details.ui.compose.rememberPanoramaBackdropPrefs
import org.skepsun.kototoro.home.ui.HOME_HERO_SECTION_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_TOTAL_LIMIT
import org.skepsun.kototoro.home.ui.HomeRecentItem
import org.skepsun.kototoro.home.ui.HomeRecommendationItem
import org.skepsun.kototoro.home.ui.HomeSummaryState
import org.skepsun.kototoro.home.ui.HomeUpdateItem
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
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
) {
    val recompositionCounter = remember { intArrayOf(0) }
    val scrollState = rememberScrollState()
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
    val posterStyle = remember(gridScale) { compactPosterRailCardStyle(gridScale) }
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
        HomeQuickAction(stringResource(R.string.favourites), R.drawable.ic_heart, actions.onLibraryOpenClick),
        HomeQuickAction(stringResource(R.string.bookmarks), R.drawable.ic_bookmark, actions.onBookmarksClick),
        HomeQuickAction(stringResource(R.string.local_storage), R.drawable.ic_storage, actions.onLocalClick),
        HomeQuickAction(stringResource(R.string.downloads), R.drawable.ic_download, actions.onDownloadsClick),
        HomeQuickAction(stringResource(R.string.random), R.drawable.ic_dice, actions.onRandomClick, !isRandomLoading),
        HomeQuickAction(stringResource(R.string.extension_management), R.drawable.ic_extension, actions.onManageSourcesClick),
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
    val heroScrollOffsetPx by remember(scrollState, heroPx) {
        derivedStateOf {
            (-scrollState.value.toFloat()).coerceIn(-heroPx.toFloat(), 0f)
        }
    }

    SideEffect {
        if (BuildConfig.DEBUG) {
            recompositionCounter[0] += 1
            Log.d(
                "HomeScreenDiag",
                "recompose#${recompositionCounter[0]} state=${state.homeDiagSignature()} hero=${heroEntries.homeHeroSignature()} listMode=$listMode gridScale=$gridScale scroll=${scrollState.value} heroPx=$heroPx heroOffset=$heroScrollOffsetPx",
            )
        }
    }


    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(scrollState)
                .padding(
                    start = systemBarsPadding.calculateLeftPadding(layoutDirection) + 8.dp,
                    top = scrollTopInset,
                    end = systemBarsPadding.calculateRightPadding(layoutDirection) + 8.dp,
                    bottom = contentPadding.calculateBottomPadding(),
                )
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasHighlights = heroEntries.isNotEmpty() ||
                state.recentHistoryItems.isNotEmpty() ||
                state.recentUpdates.isNotEmpty() ||
                state.recommendations.isNotEmpty() ||
                recentSearches.isNotEmpty()
            if (hasHighlights) {
                if (heroEntries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(heroHeightDp))
                }
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
            if (!hasHighlights && !state.isInitialized) {
                HomeLoadingSkeleton(posterStyle = posterStyle)
            }

            QuickActionsSection(actions = quickActions)
        }

        if (heroEntries.isNotEmpty()) {
            HomeHeroSection(
                entries = heroEntries,
                panoramaPrefs = homeHeroPanoramaPrefs,
                onClick = onContentClick,
                topContentInset = topInset + 8.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { translationY = heroScrollOffsetPx }
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
    val newChaptersLabel = stringResource(R.string.new_chapters)
    val historyDisplayItems = remember(historyItems) {
        historyItems.map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recent_history",
                stableKey = it.groupKey,
            )
        }
    }
    val updateDisplayItems = remember(updateItems, newChaptersLabel) {
        updateItems.map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recent_updates",
                stableKey = it.groupKey,
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
        recommendationItems.map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recommendations",
                stableKey = it.groupKey,
            )
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (updateItems.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                tonalElevation = 0.dp,
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
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
        if (historyItems.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                tonalElevation = 0.dp,
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
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
        if (recommendationItems.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                tonalElevation = 0.dp,
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
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
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
private fun HomeHeroSection(
    entries: List<HomeHeroEntry>,
    panoramaPrefs: PanoramaBackdropPrefs,
    onClick: (Content, Rect?, String?) -> Unit,
    topContentInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
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
    )
    LaunchedEffect(entries, pagerState) {
        snapshotFlow { pagerState.settledPage.coerceIn(0, entries.lastIndex) }
            .distinctUntilChanged()
            .mapNotNull { index ->
                entries.getOrNull(index)?.let { entry -> index to entry }
            }
            .collectLatest { (index, entry) ->
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HomeScreenDiag",
                        "heroPage index=$index kind=${entry.kind.name} groupKey=${entry.groupKey} contentId=${entry.content.id}",
                    )
                }
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topContentInset),
    ) {
        val pagerWidth = maxWidth.coerceAtMost(HOME_HERO_PAGER_MAX_WIDTH)
        val pageSpacing = 12.dp
        val cardWidth = calculateHomeHeroCardWidth(
            viewportWidth = pagerWidth,
            pageSpacing = pageSpacing,
        )
        val contentPadding = 16.dp
        val density = LocalDensity.current
        val contentPadPx = with(density) { contentPadding.toPx() }
        val stepPx = with(density) { (cardWidth + pageSpacing).toPx() }
        val pagerWidthPx = with(density) { pagerWidth.toPx() }
        val cardWidthPx = with(density) { cardWidth.toPx() }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(cardWidth),
                pageSpacing = pageSpacing,
                beyondViewportPageCount = 2,
                contentPadding = PaddingValues(horizontal = contentPadding),
                modifier = Modifier.width(pagerWidth),
            ) { page ->
                HomeHeroCard(
                    entry = entries[page],
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
    val shouldCrossfade = sharedTransitionScope == null || animatedVisibilityScope == null
    val imageRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = shouldCrossfade,
    )
    val coverData = remember(content.coverUrl, content.largeCoverUrl, imageRequest) {
        content.coverUrl?.takeIfUsableImageUri()
            ?: content.largeCoverUrl?.takeIfUsableImageUri()
            ?: (imageRequest?.data as? String)
    }
    LogHomeImageRequestEffect(
        slot = "hero_cover_request",
        stableKey = entry.groupKey,
        url = coverData,
        imageRequest = imageRequest,
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
        if (panoramaPrefs.isEnabled && imageRequest != null) {
            AnimatedPanoramaBackdrop(
                prefs = panoramaPrefs,
                model = imageRequest,
                placeholderMemoryCacheKey = imageRequest.memoryCacheKey,
                snapshotKey = sharedElementKey,
                contentAlpha = 0.94f,
                backgroundColor = MaterialTheme.colorScheme.surface,
            )
        } else if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = content.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    logHomeImageDiag(
                        slot = "hero_backdrop",
                        stableKey = entry.groupKey,
                        url = coverData,
                        detail = "source=${state.result.dataSource}",
                    )
                },
                onError = { state ->
                    logHomeImageDiag(
                        slot = "hero_backdrop_error",
                        stableKey = entry.groupKey,
                        url = coverData,
                        detail = state.result.throwable.message.orEmpty(),
                    )
                },
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
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)),
            ) {
                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = content.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
                            logHomeImageDiag(
                                slot = "hero_cover",
                                stableKey = entry.groupKey,
                                url = coverData,
                                detail = "source=${state.result.dataSource}",
                            )
                        },
                        onError = { state ->
                            logHomeImageDiag(
                                slot = "hero_cover_error",
                                stableKey = entry.groupKey,
                                url = coverData,
                                detail = state.result.throwable.message.orEmpty(),
                            )
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
private fun HomeRecentSearchSection(
    queries: List<String>,
    onQueryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            queries.forEach { query ->
                AssistChip(
                    onClick = { onQueryClick(query) },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = {
                        Text(
                            text = query,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
    val scrollIntensity = rememberHorizontalRailScrollIntensity(rowState)
    val showMoreButton = true
    val railPages = remember(items, listMode) {
        when (listMode) {
            ListMode.GRID -> emptyList()
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

        val railAnimationFactor = rememberRailAnimationFactor()
        when (listMode) {
            ListMode.GRID -> {
                LazyRow(
                    state = rowState,
                    flingBehavior = rememberSnapFlingBehavior(rowState),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
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
                    val horizontalPadding = 4.dp
                    LazyRow(
                        state = rowState,
                        flingBehavior = rememberSnapFlingBehavior(
                            lazyListState = rowState,
                            snapPosition = SnapPosition.Start,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(rowSpacing),
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
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

@Composable
private fun HomeListRailPage(
    items: List<HomeCoverDisplayItem>,
    listMode: ListMode,
    onItemClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                HomeListRailRowItem(
                    item = item,
                    listMode = listMode,
                    onClick = onItemClick,
                )
            }
            repeat((HOME_LIST_RAIL_PAGE_SIZE - items.size).coerceAtLeast(0)) {
                Spacer(modifier = Modifier.height(homeListRailPlaceholderHeight(listMode)))
            }
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
    val content = item.content
    val coverSize = when (listMode) {
        ListMode.LIST -> HomeListRailCoverSize(52.dp, 78.dp)
        ListMode.DETAILED_LIST -> HomeListRailCoverSize(72.dp, 108.dp)
        ListMode.GRID -> HomeListRailCoverSize(52.dp, 78.dp)
    }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val shouldCrossfadeCover = sharedTransitionScope == null || animatedVisibilityScope == null
    var coverBounds by remember(item.sectionKey, item.stableKey) { mutableStateOf<Rect?>(null) }
    val imageRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = shouldCrossfadeCover,
    )
    val coverData = remember(content.coverUrl, content.largeCoverUrl, imageRequest) {
        content.coverUrl?.takeIfUsableImageUri()
            ?: content.largeCoverUrl?.takeIfUsableImageUri()
            ?: (imageRequest?.data as? String)
    }
    LogHomeImageRequestEffect(
        slot = "list_row_cover_request",
        stableKey = item.stableKey,
        url = coverData,
        imageRequest = imageRequest,
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
                .clip(if (listMode == ListMode.DETAILED_LIST) MaterialTheme.shapes.medium else MaterialTheme.shapes.small)
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
                        logHomeImageDiag(
                            slot = "list_row_cover",
                            stableKey = item.stableKey,
                            url = coverData,
                            detail = "source=${state.result.dataSource}",
                        )
                    },
                    onError = { state ->
                        logHomeImageDiag(
                            slot = "list_row_cover_error",
                            stableKey = item.stableKey,
                            url = coverData,
                            detail = state.result.throwable.message.orEmpty(),
                        )
                    },
                )
            }
            if (content.isNsfw()) {
                ContentCardNsfwBadge(
                    metrics = badgeMetrics,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(badgeMetrics.outerPadding),
                )
            }
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
    onClick: (Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cardShape = MaterialTheme.shapes.medium
    val content = item.content
    var coverBounds by remember(item.sectionKey, item.stableKey) { mutableStateOf<Rect?>(null) }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val shouldCrossfadeCover = sharedTransitionScope == null || animatedVisibilityScope == null
    val imageRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = shouldCrossfadeCover,
    )
    val coverData = remember(content.coverUrl, content.largeCoverUrl, imageRequest) {
        content.coverUrl?.takeIfUsableImageUri()
            ?: content.largeCoverUrl?.takeIfUsableImageUri()
            ?: (imageRequest?.data as? String)
    }
    LogHomeImageRequestEffect(
        slot = "grid_row_cover_request",
        stableKey = item.stableKey,
        url = coverData,
        imageRequest = imageRequest,
    )
    val badgeMetrics = remember(posterStyle.itemWidth) { contentCardBadgeMetricsFor(posterStyle.itemWidth) }
    val sharedElementKey = remember(item.sectionKey, item.stableKey, content.coverUrl, content.source.name) {
        contentCoverSharedKey(
            content.source.name,
            content.coverUrl.orEmpty(),
            instanceKey = "home_row_${item.sectionKey}_${item.stableKey}",
        )
    }

    Column(
        modifier = modifier
            .width(posterStyle.itemWidth)
            .clickable { onClick(coverBounds, sharedElementKey) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(posterStyle.posterHeight)
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
                .clip(cardShape)
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
                        logHomeImageDiag(
                            slot = "grid_row_cover",
                            stableKey = item.stableKey,
                            url = coverData,
                            detail = "source=${state.result.dataSource}",
                        )
                    },
                    onError = { state ->
                        logHomeImageDiag(
                            slot = "grid_row_cover_error",
                            stableKey = item.stableKey,
                            url = coverData,
                            detail = state.result.throwable.message.orEmpty(),
                        )
                    },
                )
            }
            if (content.isNsfw()) {
                ContentCardNsfwBadge(
                    metrics = badgeMetrics,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(badgeMetrics.outerPadding),
                )
            }
        }
        Text(
            text = content.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.supportingText?.let { supportingText ->
            Text(
                text = supportingText.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun itemNewChaptersText(label: String, count: Int): String = "$label $count"

private fun calculateHomeHeroCardWidth(
    viewportWidth: Dp,
    pageSpacing: Dp,
): Dp {
    val referenceViewportWidth = viewportWidth.coerceAtMost(HOME_HERO_REFERENCE_VIEWPORT_WIDTH)
    return (referenceViewportWidth - (HOME_HERO_COMPACT_EDGE_PEEK * 2) - (pageSpacing * 2))
        .coerceAtLeast(HOME_HERO_CARD_MIN_WIDTH)
        .coerceAtMost(HOME_HERO_CARD_MAX_WIDTH)
}

private fun calculateHomeListRailPageWidth(maxWidth: Dp, listMode: ListMode): Dp {
    val referenceWidth = maxWidth.coerceAtMost(HOME_LIST_RAIL_REFERENCE_VIEWPORT_WIDTH)
    val targetWidth = when (listMode) {
        ListMode.DETAILED_LIST -> referenceWidth * HOME_DETAILED_RAIL_PAGE_WIDTH_RATIO
        ListMode.LIST -> referenceWidth * HOME_LIST_RAIL_PAGE_WIDTH_RATIO
        ListMode.GRID -> referenceWidth * HOME_LIST_RAIL_PAGE_WIDTH_RATIO
    }
    val maxPageWidth = if (listMode == ListMode.DETAILED_LIST) {
        HOME_DETAILED_RAIL_PAGE_MAX_WIDTH
    } else {
        HOME_LIST_RAIL_PAGE_MAX_WIDTH
    }
    return targetWidth.coerceAtMost(maxPageWidth).coerceAtLeast(HOME_LIST_RAIL_PAGE_MIN_WIDTH)
}

private fun homeListRailPlaceholderHeight(listMode: ListMode): Dp = when (listMode) {
    ListMode.LIST -> 78.dp
    ListMode.DETAILED_LIST -> 108.dp
    ListMode.GRID -> 0.dp
}

@Immutable
private data class HomeCoverDisplayItem(
    val content: Content,
    val sectionKey: String,
    val stableKey: Long,
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
private val HOME_HERO_CARD_MIN_WIDTH = 264.dp
private val HOME_HERO_CARD_MAX_WIDTH = 360.dp
private val HOME_HERO_PAGER_MAX_WIDTH = 1240.dp
private val HOME_HERO_REFERENCE_VIEWPORT_WIDTH = 392.dp
private val HOME_HERO_COMPACT_EDGE_PEEK = 4.dp
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
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
                maxWidth >= 360.dp -> 4
                else -> 3
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = action.enabled, onClick = action.onClick)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (action.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = action.enabled, onClick = action.onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (action.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
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

    updateItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_SECTION_LIMIT)
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
        .take(HOME_HERO_SECTION_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.RECOMMENDATION,
                    content = item.content,
                    groupKey = item.groupKey,
                ),
            )
        }

    historyItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_SECTION_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.HISTORY,
                    content = item.content,
                    groupKey = item.groupKey,
                ),
            )
        }

    return entries
}

private fun HomeSummaryState.homeDiagSignature(): String {
    return buildString {
        append("tab=").append(selectedTab)
        append(" initialized=").append(isInitialized)
        append(" resume=").append(resumeState.content?.id).append('@').append(resumeState.groupKey)
        append(" history=").append(recentHistoryItems.joinToString(limit = 6) { "${it.groupKey}:${it.content.id}" })
        append(" updates=").append(recentUpdates.joinToString(limit = 6) { "${it.groupKey}:${it.content.id}:${it.newChapters}" })
        append(" recommendations=").append(recommendations.joinToString(limit = 6) { "${it.groupKey}:${it.content.id}" })
        append(" searches=").append(recentSearches.joinToString(limit = 4) { it.query })
    }
}

private fun List<HomeHeroEntry>.homeHeroSignature(): String {
    return joinToString(limit = 6) { "${it.kind.name}:${it.groupKey}:${it.content.id}" }
}

@Composable
private fun rememberHomeCoverRequest(
    context: android.content.Context,
    content: Content,
    allowCrossfade: Boolean,
): ImageRequest? {
    val primaryCoverUrl = content.coverUrl?.takeIfUsableImageUri()
    val fallbackCoverUrl = content.largeCoverUrl?.takeIfUsableImageUri()
    val ownerKey = remember(content.id, content.url, content.publicUrl) {
        content.url.takeIf { it.isNotBlank() }
            ?: content.publicUrl.takeIf { it.isNotBlank() }
            ?: content.id.toString()
    }
    return remember(
        context,
        content.id,
        content.source.name,
        ownerKey,
        primaryCoverUrl,
        fallbackCoverUrl,
        allowCrossfade,
    ) {
        val resolvedCoverUrl = primaryCoverUrl ?: fallbackCoverUrl
        if (resolvedCoverUrl != null) {
            val cacheKey = sharedCoverMemoryCacheKey(
                sourceName = content.source.name,
                ownerKey = ownerKey,
                url = resolvedCoverUrl,
            )
            return@remember ImageRequest.Builder(context)
                .data(resolvedCoverUrl)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .crossfade(allowCrossfade)
                .apply { mangaExtra(content) }
                .build()
        }
        if (content.url.startsWith("tvbox://item/")) {
            val fallbackCacheKey = sharedCoverMemoryCacheKey(
                sourceName = content.source.name,
                ownerKey = ownerKey,
                url = "tvbox-search-cover:${content.url}",
            )
            return@remember ImageRequest.Builder(context)
                .data(tvboxSearchCoverModel(content))
                .memoryCacheKey(fallbackCacheKey)
                .diskCacheKey(fallbackCacheKey)
                .crossfade(allowCrossfade)
                .mangaExtra(content)
                .build()
        }
        null
    }
}

private fun logHomeImageDiag(
    slot: String,
    stableKey: Long,
    url: String?,
    detail: String,
) {
    if (BuildConfig.DEBUG) {
        Log.d(
            "HomeScreenImageDiag",
            "$slot key=$stableKey url=${url.orEmpty()} $detail",
        )
    }
}

@Composable
private fun LogHomeImageRequestEffect(
    slot: String,
    stableKey: Long,
    url: String?,
    imageRequest: ImageRequest?,
) {
    LaunchedEffect(imageRequest) {
        if (imageRequest != null) {
            logHomeImageDiag(
                slot = slot,
                stableKey = stableKey,
                url = url,
                detail = "memoryKey=${imageRequest.memoryCacheKey.orEmpty()} diskKey=${imageRequest.diskCacheKey.orEmpty()}",
            )
        }
    }
}
