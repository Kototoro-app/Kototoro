package org.skepsun.kototoro.explore.ui.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest.Builder
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.ContentSourceResolvedIcon
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.VerticalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.clearFailedContentSourceIcons
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.logHeroTransition
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.image.panoramaBlur
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.discover.ui.DiscoverViewModel
import org.skepsun.kototoro.discover.ui.compose.DiscoverHeroCarousel
import org.skepsun.kototoro.discover.ui.compose.discoverHeroHeight
import org.skepsun.kototoro.discover.ui.model.DiscoverCarouselRow
import org.skepsun.kototoro.explore.ui.ExploreViewModel
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.secondaryTitleText
import org.skepsun.kototoro.list.ui.model.supportingText
import org.skepsun.kototoro.list.ui.model.buildInfoText
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale

private const val BrowseLoadMoreBuffer = 4
private val BrowseHeroContentOverlap = 56.dp
private val SourceGridHorizontalPadding = AppLayoutTokens.compactItemHorizontalPadding

private inline fun traceExploreRoute(message: () -> String) = Unit

internal data class SourceQuickAccessMetrics(
    val preferredColumns: Int,
    val minCardWidth: androidx.compose.ui.unit.Dp,
    val cardHeight: androidx.compose.ui.unit.Dp,
    val gridSpacing: androidx.compose.ui.unit.Dp,
    val iconContainerSize: androidx.compose.ui.unit.Dp,
    val iconSize: androidx.compose.ui.unit.Dp,
    val titleTextSize: TextUnit,
)

@Immutable
private data class ExploreScreenPrefs(
    val gridScale: Float,
    val isSourcesGroupedByLanguage: Boolean,
    val browseListMode: ListMode,
    val isBrowseTrackingRecommendationsEnabled: Boolean,
    val isBrowseMoreTrackingRecommendationsEnabled: Boolean,
    val panoramaCoverBlur: Int,
)

private data class SourceQuickAccessGroup(
    val title: String?,
    val sources: List<ContentSourceItem>,
)

private data class SourceQuickAccessRows(
    val title: String?,
    val rows: List<List<ContentSourceItem>>,
)

private data class BrowseSourceItems(
    val sources: List<ContentSourceItem>,
    val isLoadingOnly: Boolean,
)

private fun prepareBrowseSourceItems(items: List<ListModel>): BrowseSourceItems {
    val sources = ArrayList<ContentSourceItem>()
    items.forEach { item ->
        if (item is ContentSourceItem) {
            sources += item
        }
    }
    return BrowseSourceItems(
        sources = sources,
        isLoadingOnly = sources.isEmpty() && items.any { it is LoadingState },
    )
}

private data class BrowseShowcaseRow(
    val row: DiscoverCarouselRow,
    val items: List<ContentListModel>,
)

private data class BrowseDiscoverItems(
    val heroRow: DiscoverCarouselRow?,
    val heroItems: List<ContentListModel>,
    val showcaseRows: List<BrowseShowcaseRow>,
    val popularItems: List<ContentListModel>,
    val isLoadingOnly: Boolean,
)

private fun prepareBrowseDiscoverItems(items: List<ListModel>): BrowseDiscoverItems {
    val carouselRows = ArrayList<DiscoverCarouselRow>()
    var isLoadingOnly = items.size <= 1
    if (isLoadingOnly) {
        isLoadingOnly = items.any { it is LoadingState }
    }
    items.forEach { item ->
        if (item is DiscoverCarouselRow) {
            carouselRows += item
        }
    }

    var heroRow: DiscoverCarouselRow? = null
    var heroItems: List<ContentListModel> = emptyList()
    val showcaseCandidates = ArrayList<BrowseShowcaseRow>()
    val popularItems = ArrayList<ContentListModel>()
    val popularIds = HashSet<Long>()

    carouselRows.forEach { row ->
        val rowItems = row.items
            .asSequence()
            .filterIsInstance<ContentListModel>()
            .toList()
        if (heroRow == null && rowItems.isNotEmpty()) {
            heroRow = row
            heroItems = rowItems.take(6)
        } else {
            if (rowItems.isNotEmpty()) {
                showcaseCandidates += BrowseShowcaseRow(row = row, items = rowItems.take(12))
            }
            rowItems.forEach { item ->
                if (popularIds.add(item.id)) {
                    popularItems += item
                }
            }
        }
    }

    return BrowseDiscoverItems(
        heroRow = heroRow,
        heroItems = heroItems,
        showcaseRows = showcaseCandidates,
        popularItems = popularItems,
        isLoadingOnly = isLoadingOnly,
    )
}

internal fun sourceQuickAccessMetrics(gridScale: Float): SourceQuickAccessMetrics {
    val titleTextSize = resolveSourceQuickAccessTitleTextSize(gridScale)
    return when {
        gridScale <= 0.8f -> SourceQuickAccessMetrics(
            preferredColumns = 5,
            minCardWidth = 64.dp,
            cardHeight = 92.dp,
            gridSpacing = 4.dp,
            iconContainerSize = 56.dp,
            iconSize = 46.dp,
            titleTextSize = titleTextSize,
        )
        gridScale < 1.15f -> SourceQuickAccessMetrics(
            preferredColumns = 4,
            minCardWidth = 80.dp,
            cardHeight = 108.dp,
            gridSpacing = 5.dp,
            iconContainerSize = 68.dp,
            iconSize = 56.dp,
            titleTextSize = titleTextSize,
        )
        else -> SourceQuickAccessMetrics(
            preferredColumns = 3,
            minCardWidth = 108.dp,
            cardHeight = 134.dp,
            gridSpacing = 6.dp,
            iconContainerSize = 88.dp,
            iconSize = 72.dp,
            titleTextSize = titleTextSize,
        )
    }
}

internal fun resolveSourceQuickAccessTitleTextSize(gridScale: Float): TextUnit {
    val normalized = ((gridScale.coerceIn(0.5f, 1.5f) - 0.5f) / 1f).coerceIn(0f, 1f)
    return (10f + 4f * normalized).sp
}

private fun calculateSourceGridColumns(
    availableWidth: androidx.compose.ui.unit.Dp,
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
): Int {
    if (browseListMode != ListMode.GRID && browseListMode != ListMode.COMPACT_GRID) {
        return 1
    }
    val spacing = metrics.gridSpacing
    val rawColumns = ((availableWidth + spacing) / (metrics.minCardWidth + spacing))
        .toInt()
        .coerceAtLeast(1)
    return rawColumns.coerceAtLeast(metrics.preferredColumns)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun KototoroExploreHostRoute(
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    exploreViewModel: ExploreViewModel = hiltViewModel(),
    discoverViewModel: DiscoverViewModel = hiltViewModel(),
    onSourceSelectionTopBarChanged: (ExploreSourceSelectionTopBarState?) -> Unit = {},
    onNavigateToDetails: ((DetailsOrigin, String?) -> Unit)? = null,
    onOpenSourceList: ((org.skepsun.kototoro.parsers.model.ContentSource) -> Unit)? = null,
) {
    val sourceItems by exploreViewModel.content.collectAsStateWithLifecycle()
    val discoverItems by discoverViewModel.content.collectAsStateWithLifecycle()
    val isDiscoverLoading by discoverViewModel.isLoading.collectAsStateWithLifecycle()
    val availableServices by discoverViewModel.availableServices.collectAsStateWithLifecycle()
    val activeService by discoverViewModel.activeService.collectAsStateWithLifecycle()
    val query by discoverViewModel.query.collectAsStateWithLifecycle()
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    var savedBrowseListIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedBrowseListOffset by rememberSaveable { mutableIntStateOf(0) }
    var shouldRestoreBrowseScroll by rememberSaveable { mutableStateOf(false) }
    var hasLeftBrowse by rememberSaveable { mutableStateOf(false) }
    var canRestoreBrowseScroll by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    LaunchedEffect(discoverViewModel, context) {
        discoverViewModel.bangumiRecommendationLoadFailures.collect {
            Toast.makeText(context, R.string.bangumi_recommendations_load_failed_hint, Toast.LENGTH_LONG).show()
        }
    }
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? androidx.activity.ComponentActivity
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_SOURCES_GROUPED_BY_LANGUAGE,
        AppSettings.KEY_LIST_MODE_BROWSE,
        AppSettings.KEY_BROWSE_TRACKING_RECOMMENDATIONS,
        AppSettings.KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS,
        AppSettings.KEY_PANORAMA_BLUR,
    ) {
        ExploreScreenPrefs(
            gridScale = gridSize / 100f,
            isSourcesGroupedByLanguage = isSourcesGroupedByLanguage,
            browseListMode = browseListMode,
            isBrowseTrackingRecommendationsEnabled = isBrowseTrackingRecommendationsEnabled,
            isBrowseMoreTrackingRecommendationsEnabled = isBrowseMoreTrackingRecommendationsEnabled,
            panoramaCoverBlur = panoramaCoverBlur,
        )
    }
    val gridScale = screenPrefs.gridScale
    val panoramaCoverBlur = screenPrefs.panoramaCoverBlur
    val isSourcesGroupedByLanguage = screenPrefs.isSourcesGroupedByLanguage
    val browseListMode = screenPrefs.browseListMode
    val isBrowseTrackingRecommendationsEnabled = screenPrefs.isBrowseTrackingRecommendationsEnabled
    val isBrowseMoreTrackingRecommendationsEnabled = screenPrefs.isBrowseMoreTrackingRecommendationsEnabled
    val posterStyle = remember(gridScale) { compactPosterRailCardStyle(gridScale) }
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val browseHeroHeight = discoverHeroHeight(
        isLandscape = isLandscape,
        detachedBottomContent = true,
    ) + contentPadding.calculateTopPadding()
    val browseHeroHeightPx = with(density) { browseHeroHeight.toPx() }
    // 实时读取 LazyColumn 第一个 item 的滚动偏移，驱动 Hero 跟随滚动
    var selectedSourceIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val hapticFeedback = LocalHapticFeedback.current
    val browseSourceItems = remember(sourceItems) {
        prepareBrowseSourceItems(sourceItems)
    }
    val sources = browseSourceItems.sources
    val sourceInfoById = remember(sources) {
        buildMap(sources.size) {
            sources.forEach { source ->
                put(source.id, source.source)
            }
        }
    }
    val browseDiscoverItems = remember(discoverItems) { prepareBrowseDiscoverItems(discoverItems) }
    val heroRow = if (isBrowseTrackingRecommendationsEnabled) browseDiscoverItems.heroRow else null
    val heroItems = if (isBrowseTrackingRecommendationsEnabled) browseDiscoverItems.heroItems else emptyList()
    val shouldShowMoreTrackingRecommendations = isBrowseTrackingRecommendationsEnabled &&
        isBrowseMoreTrackingRecommendationsEnabled
    val showcaseRows = if (shouldShowMoreTrackingRecommendations) browseDiscoverItems.showcaseRows else emptyList()
    val popularItems = if (shouldShowMoreTrackingRecommendations) browseDiscoverItems.popularItems else emptyList()
    val isSourcesLoadingOnly = browseSourceItems.isLoadingOnly
    val isDiscoverLoadingOnly = browseDiscoverItems.isLoadingOnly
    val shouldShowBrowseHero = isBrowseTrackingRecommendationsEnabled
    val isBrowseContentReady = sources.isNotEmpty() ||
        heroItems.isNotEmpty() ||
        showcaseRows.isNotEmpty() ||
        popularItems.isNotEmpty()
    val currentSourceTrace by rememberUpdatedState(sourceItems.size to sources.size)
    LaunchedEffect(sourceItems, discoverItems, isDiscoverLoading) {
        traceExploreRoute {
            "content emitted lifecycle=${lifecycleOwner.lifecycle.currentState} " +
                "sourceModels=${sourceItems.size} sources=${sources.size} sourceLoading=$isSourcesLoadingOnly " +
                "discoverModels=${discoverItems.size} discoverLoading=$isDiscoverLoading"
        }
    }
    LaunchedEffect(contentPadding.calculateTopPadding(), contentPadding.calculateBottomPadding()) {
        traceExploreRoute {
            "insets top=${contentPadding.calculateTopPadding()} bottom=${contentPadding.calculateBottomPadding()} " +
                "list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset} " +
                "saved=$savedBrowseListIndex:$savedBrowseListOffset restore=$shouldRestoreBrowseScroll " +
                "left=$hasLeftBrowse canRestore=$canRestoreBrowseScroll"
        }
    }
    val heroOverlapDp = if (shouldShowBrowseHero) {
        BrowseHeroContentOverlap
    } else {
        0.dp
    }
    val heroHeightDp by remember(browseHeroHeight, heroOverlapDp, shouldShowBrowseHero) {
        derivedStateOf {
            if (!shouldShowBrowseHero) {
                0.dp
            } else {
                (browseHeroHeight - heroOverlapDp).coerceAtLeast(0.dp)
            }
        }
    }
    val selectedSources = remember(selectedSourceIds, sourceInfoById) {
        selectedSourceIds.mapNotNull(sourceInfoById::get)
    }
    val sourceMetrics = remember(gridScale) { sourceQuickAccessMetrics(gridScale) }
    var isSourcesExpanded by rememberSaveable(sources.size, browseListMode, isSourcesGroupedByLanguage) {
        mutableStateOf(false)
    }
    val sourceContentWidth = remember(configuration.screenWidthDp, contentPadding, layoutDirection) {
        configuration.screenWidthDp.dp -
            contentPadding.calculateStartPadding(layoutDirection) -
            contentPadding.calculateEndPadding(layoutDirection) -
            SourceGridHorizontalPadding * 2
    }
    val sourceColumns = remember(sourceContentWidth, sourceMetrics, browseListMode) {
        calculateSourceGridColumns(
            availableWidth = sourceContentWidth,
            metrics = sourceMetrics,
            browseListMode = browseListMode,
        )
    }
    val sourceCollapsedVisibleCount = remember(sourceColumns) { sourceColumns * 5 }
    val sourceGroups = remember(sources, isSourcesGroupedByLanguage, context) {
        sources.toQuickAccessGroups(
            isGroupedByLanguage = isSourcesGroupedByLanguage,
            context = context,
        )
    }
    val shouldForceSourcesExpanded = !shouldShowMoreTrackingRecommendations
    val areSourcesExpanded = shouldForceSourcesExpanded || isSourcesExpanded
    val visibleSourceGroups = remember(sourceGroups, sourceCollapsedVisibleCount, areSourcesExpanded) {
        sourceGroups.takeVisibleSourceGroups(
            maxSources = if (areSourcesExpanded) Int.MAX_VALUE else sourceCollapsedVisibleCount,
        )
    }
    val visibleSourceRows = remember(visibleSourceGroups, sourceColumns) {
        visibleSourceGroups.map { group ->
            SourceQuickAccessRows(
                title = group.title,
                rows = group.sources.chunked(sourceColumns),
            )
        }
    }
    val hasMoreSources = !shouldForceSourcesExpanded && sources.size > sourceCollapsedVisibleCount

    BackHandler(enabled = selectedSourceIds.isNotEmpty()) {
        selectedSourceIds = emptySet()
    }

    SideEffect {
        if (selectedSourceIds.isNotEmpty()) {
            val isSingleSelection = selectedSources.size == 1
            val canPin = selectedSources.isNotEmpty() && selectedSources.all { !it.isPinned }
            val canUnpin = selectedSources.isNotEmpty() && selectedSources.all { it.isPinned }
            val canDisable = selectedSources.isNotEmpty() && !exploreViewModel.isAllSourcesEnabled.value && selectedSources.all {
                val unwrapped = it.mangaSource.unwrap()
                !unwrapped.isLocal && unwrapped !is ExternalContentSource
            }
            val canDelete = selectedSources.isNotEmpty() && selectedSources.all { it.mangaSource is ExternalContentSource }
            val markEmptyTitleRes = if (selectedSources.all { it.availability == ContentSourceAvailability.EMPTY }) {
                R.string.source_mark_available
            } else {
                R.string.source_mark_empty
            }

            onSourceSelectionTopBarChanged(
                ExploreSourceSelectionTopBarState(
                    selectedCount = selectedSourceIds.size,
                    isSingleSelection = isSingleSelection,
                    canPin = canPin,
                    canUnpin = canUnpin,
                    canDisable = canDisable,
                    canDelete = canDelete,
                    markEmptyTitleRes = markEmptyTitleRes,
                    onClearSelection = { selectedSourceIds = emptySet() },
                    onSettings = {
                        selectedSources.singleOrNull()?.let { appRouter.openSourceSettings(it) }
                        selectedSourceIds = emptySet()
                    },
                    onDisable = {
                        exploreViewModel.disableSources(selectedSources)
                        selectedSourceIds = emptySet()
                    },
                    onDelete = {
                        selectedSources.forEach { item ->
                            (item.mangaSource as? ExternalContentSource)?.let { source ->
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_DELETE,
                                    android.net.Uri.parse("package:${source.packageName}"),
                                )
                                activity?.startActivity(intent)
                            }
                        }
                        selectedSourceIds = emptySet()
                    },
                    onShortcut = {
                        selectedSources.singleOrNull()?.let { exploreViewModel.requestPinShortcut(it) }
                        selectedSourceIds = emptySet()
                    },
                    onPin = {
                        exploreViewModel.setSourcesPinned(selectedSources, isPinned = true)
                        selectedSourceIds = emptySet()
                    },
                    onUnpin = {
                        exploreViewModel.setSourcesPinned(selectedSources, isPinned = false)
                        selectedSourceIds = emptySet()
                    },
                    onToggleEmptyAvailability = {
                        exploreViewModel.toggleEmptySourceAvailability(selectedSources)
                        selectedSourceIds = emptySet()
                    },
                ),
            )
        } else {
            onSourceSelectionTopBarChanged(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onSourceSelectionTopBarChanged(null)
        }
    }

    DisposableEffect(lifecycleOwner) {
        traceExploreRoute {
            "route mounted lifecycle=${lifecycleOwner.lifecycle.currentState} " +
                "list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset} " +
                "saved=$savedBrowseListIndex:$savedBrowseListOffset restore=$shouldRestoreBrowseScroll " +
                "left=$hasLeftBrowse canRestore=$canRestoreBrowseScroll"
        }
        val observer = LifecycleEventObserver { _, event ->
            traceExploreRoute {
                val (sourceModelCount, sourceCount) = currentSourceTrace
                "lifecycle event=$event state=${lifecycleOwner.lifecycle.currentState} " +
                    "sourceModels=$sourceModelCount sources=$sourceCount " +
                    "list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset} " +
                    "saved=$savedBrowseListIndex:$savedBrowseListOffset restore=$shouldRestoreBrowseScroll " +
                    "left=$hasLeftBrowse canRestore=$canRestoreBrowseScroll"
            }
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    if (shouldRestoreBrowseScroll) {
                        hasLeftBrowse = true
                        canRestoreBrowseScroll = false
                        return@LifecycleEventObserver
                    }
                    val index = listState.firstVisibleItemIndex
                    val offset = listState.firstVisibleItemScrollOffset
                    if (index != 0 || offset != 0) {
                        savedBrowseListIndex = index
                        savedBrowseListOffset = offset
                        shouldRestoreBrowseScroll = true
                    } else {
                        savedBrowseListIndex = 0
                        savedBrowseListOffset = 0
                        shouldRestoreBrowseScroll = true
                    }
                    hasLeftBrowse = true
                    canRestoreBrowseScroll = false
                }
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    if (shouldRestoreBrowseScroll && hasLeftBrowse) {
                        canRestoreBrowseScroll = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            traceExploreRoute {
                "route disposed lifecycle=${lifecycleOwner.lifecycle.currentState} " +
                    "list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset} " +
                    "saved=$savedBrowseListIndex:$savedBrowseListOffset restore=$shouldRestoreBrowseScroll " +
                    "left=$hasLeftBrowse canRestore=$canRestoreBrowseScroll"
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentDiscoverLoading = androidx.compose.runtime.rememberUpdatedState(isDiscoverLoading)

    LaunchedEffect(
        isBrowseContentReady,
        shouldRestoreBrowseScroll,
        canRestoreBrowseScroll,
        savedBrowseListIndex,
        savedBrowseListOffset,
        shouldShowBrowseHero,
    ) {
        if (shouldRestoreBrowseScroll || canRestoreBrowseScroll) {
            traceExploreRoute {
                "restore evaluate contentReady=$isBrowseContentReady hero=$shouldShowBrowseHero " +
                    "list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset} " +
                    "saved=$savedBrowseListIndex:$savedBrowseListOffset restore=$shouldRestoreBrowseScroll " +
                    "left=$hasLeftBrowse canRestore=$canRestoreBrowseScroll"
            }
        }
        if (!shouldRestoreBrowseScroll || !canRestoreBrowseScroll || !isBrowseContentReady) {
            return@LaunchedEffect
        }
        if (savedBrowseListIndex == 0 &&
            savedBrowseListOffset == 0 &&
            isBrowseTrackingRecommendationsEnabled &&
            !shouldShowBrowseHero
        ) {
            return@LaunchedEffect
        }
        val targetIndex = savedBrowseListIndex.coerceAtLeast(0)
        val totalItems = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { it > targetIndex }
            .first()
        val restoreIndex = targetIndex.coerceAtMost(totalItems - 1)
        val restoreOffset = savedBrowseListOffset
        if (listState.firstVisibleItemIndex == restoreIndex &&
            listState.firstVisibleItemScrollOffset == restoreOffset
        ) {
            traceExploreRoute { "restore skipped alreadyAt=$restoreIndex:$restoreOffset" }
            shouldRestoreBrowseScroll = false
            hasLeftBrowse = false
            canRestoreBrowseScroll = false
            return@LaunchedEffect
        }
        traceExploreRoute {
            "restore started target=$restoreIndex:$restoreOffset totalItems=$totalItems " +
                "current=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}"
        }
        repeat(if (restoreIndex == 0 && restoreOffset == 0) 3 else 1) {
            listState.scrollToItem(
                index = restoreIndex,
                scrollOffset = restoreOffset,
            )
            yield()
            if (listState.firstVisibleItemIndex == restoreIndex &&
                listState.firstVisibleItemScrollOffset == restoreOffset
            ) {
                return@repeat
            }
        }
        if (listState.firstVisibleItemIndex != restoreIndex ||
            listState.firstVisibleItemScrollOffset != restoreOffset
        ) {
            traceExploreRoute {
                "restore mismatch target=$restoreIndex:$restoreOffset " +
                    "actual=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}"
            }
            return@LaunchedEffect
        }
        traceExploreRoute {
            "restore completed target=$restoreIndex:$restoreOffset " +
                "actual=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}"
        }
        shouldRestoreBrowseScroll = false
        hasLeftBrowse = false
        canRestoreBrowseScroll = false
    }

    LaunchedEffect(listState, query, popularItems.size) {
        if (query.isNotBlank() || popularItems.isEmpty()) {
            return@LaunchedEffect
        }
        listState.maybeTriggerBrowseLoadMore(
            itemCount = popularItems.size,
            isLoading = { currentDiscoverLoading.value },
            onLoadMore = discoverViewModel::loadNextPage,
        )
    }

    fun markBrowseDetailsNavigation() {
        savedBrowseListIndex = listState.firstVisibleItemIndex
        savedBrowseListOffset = listState.firstVisibleItemScrollOffset
        shouldRestoreBrowseScroll = true
        hasLeftBrowse = false
        canRestoreBrowseScroll = false
    }

    KototoroPullToRefreshBox(
        isRefreshing = isDiscoverLoading && !isDiscoverLoadingOnly,
        onRefresh = {
            clearFailedContentSourceIcons()
            discoverViewModel.refresh()
        },
        modifier = Modifier.fillMaxSize(),
        indicatorTopInset = contentPadding,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // ===== 内容流 =====
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = if (shouldShowBrowseHero) 0.dp else contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 120.dp,
                ),
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 占位 Spacer，给 Hero Overlay 留空间
                if (shouldShowBrowseHero) {
                    item(key = "discover_hero_spacer") {
                        Spacer(modifier = Modifier.height(heroHeightDp))
                    }
                }

                sourceQuickAccessItems(
                    metrics = sourceMetrics,
                    browseListMode = browseListMode,
                    columns = sourceColumns,
                    visibleGroups = visibleSourceRows,
                    selectedSourceIds = selectedSourceIds,
                    hasMoreSources = hasMoreSources,
                    isExpanded = areSourcesExpanded,
                    topBackgroundOverlap = heroOverlapDp,
                    onToggleExpanded = { isSourcesExpanded = !isSourcesExpanded },
                    onManageClick = appRouter::openManageSources,
                    onSourceClick = { source ->
                        if (selectedSourceIds.isNotEmpty()) {
                            hapticFeedback.performSelectionHapticFeedback()
                            selectedSourceIds = selectedSourceIds.toggle(source.id)
                        } else {
                            onOpenSourceList?.invoke(source.source) ?: appRouter.openList(source.source, null, null)
                        }
                    },
                    onSourceLongClick = { source ->
                        selectedSourceIds = selectedSourceIds.toggle(source.id)
                    },
                )
                if (isSourcesLoadingOnly) {
                    item(key = "source_quick_access_loading", contentType = "source_quick_access_loading") {
                        BrowseSourcesSkeleton(
                            metrics = sourceMetrics,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(start = CompactTopBarHorizontalPadding, end = CompactTopBarHorizontalPadding, bottom = 36.dp),
                        )
                    }
                }

                items(
                    items = showcaseRows,
                    key = { "showcase_${it.row.category.id}" },
                    contentType = { "showcase_row" },
                ) { showcaseRow ->
                    val row = showcaseRow.row
                    if (showcaseRow.items.isNotEmpty()) {
                        TrackingCategoryRow(
                            rowKey = row.category.id,
                            title = stringResource(row.category.nameResId),
                            items = showcaseRow.items,
                            posterStyle = posterStyle,
                            modifier = Modifier.padding(horizontal = CompactTopBarHorizontalPadding, vertical = 5.dp),
                            onItemClick = { item, sharedElementKey ->
                                markBrowseDetailsNavigation()
                                val didNavigate = openTrackingItem(
                                    appRouter = appRouter,
                                    discoverViewModel = discoverViewModel,
                                    availableServices = availableServices,
                                    item = item,
                                    sharedElementKey = sharedElementKey,
                                    onNavigateToDetails = onNavigateToDetails,
                                )
                                if (!didNavigate) {
                                    shouldRestoreBrowseScroll = false
                                }
                            },
                            onMoreClick = {
                                activeService?.let { service ->
                                    appRouter.openTrackingDiscoveryCategory(service, row.category.id, row.category.nameResId)
                                }
                            },
                        )
                    }
                }

                if (popularItems.isNotEmpty()) {
                    item(key = "popular_header") {
                        BrowsePopularHeader(
                            title = stringResource(R.string.popular),
                            modifier = Modifier.padding(horizontal = CompactTopBarHorizontalPadding, vertical = 5.dp),
                        )
                    }
                    itemsIndexed(
                        items = popularItems,
                        key = { _, item -> "popular_${item.id}" },
                        contentType = { _, _ -> "popular_item" },
                    ) { index, item ->
                        val popularItemKey = "popular_${item.id}"
                        VerticalRailAnimatedVisibility(
                            animationKey = popularItemKey,
                            index = index + showcaseRows.size + 1,
                            listState = listState,
                            enableScrollLinkedAnimation = false,
                            scaleFactor = 0f,
                        ) { animatedModifier ->
                            val sharedElementKey = contentCoverSharedKey(
                                item.manga.source.name,
                                item.manga.coverUrl.orEmpty(),
                                instanceKey = "explore_popular_${item.id}",
                            )
                            BrowsePopularListItem(
                                item = item,
                                posterStyle = posterStyle,
                                sharedElementKey = sharedElementKey,
                                panoramaCoverBlur = panoramaCoverBlur,
                                modifier = animatedModifier.padding(horizontal = CompactTopBarHorizontalPadding, vertical = 5.dp),
                                onClick = {
                                    markBrowseDetailsNavigation()
                                    val didNavigate = openTrackingItem(
                                        appRouter = appRouter,
                                        discoverViewModel = discoverViewModel,
                                        availableServices = availableServices,
                                        item = item,
                                        sharedElementKey = sharedElementKey,
                                        onNavigateToDetails = onNavigateToDetails,
                                    )
                                    if (!didNavigate) {
                                        shouldRestoreBrowseScroll = false
                                    }
                                },
                            )
                        }
                    }
                }

                if (isDiscoverLoading && popularItems.isNotEmpty()) {
                    item(key = "popular_loading") {
                        BrowsePopularLoadingSection(
                            posterStyle = posterStyle,
                        modifier = Modifier.padding(horizontal = CompactTopBarHorizontalPadding, vertical = 5.dp),
                        )
                    }
                }
            }

            // ===== Hero Overlay（跟随滚动，无 spacing 污染）=====
            if (shouldShowBrowseHero) {
                BrowseHeroBlock(
                    title = heroRow?.category?.let { stringResource(it.nameResId) }
                        ?: stringResource(R.string.discover),
                    heroItems = heroItems,
                    activeService = activeService,
                    availableServices = availableServices,
                    isLoadingOnly = isDiscoverLoadingOnly,
                    topContentInset = contentPadding.calculateTopPadding(),
                    settings = settings,
                    onSelectService = discoverViewModel::selectService,
                    onOpenSchedule = activeService?.let { service ->
                        val scheduleCategory = discoverViewModel.getScheduleCategory(service)
                        if (scheduleCategory == null) {
                            null
                        } else {
                            {
                                appRouter.openTrackingDiscoveryCategory(
                                    service,
                                    scheduleCategory.id,
                                    scheduleCategory.nameResId,
                                )
                            }
                        }
                    },
                    onHeroItemClick = { item, sharedElementKey ->
                        markBrowseDetailsNavigation()
                        val didNavigate = openTrackingItem(
                            appRouter = appRouter,
                            discoverViewModel = discoverViewModel,
                            availableServices = availableServices,
                            item = item,
                            sharedElementKey = sharedElementKey,
                            onNavigateToDetails = onNavigateToDetails,
                        )
                        if (!didNavigate) {
                            shouldRestoreBrowseScroll = false
                        }
                    },
                    sharedElementKeyForItem = { item, _ ->
                        contentCoverSharedKey(
                            item.manga.source.name,
                            item.manga.coverUrl.orEmpty(),
                            instanceKey = "explore_hero_${item.id}",
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .graphicsLayer {
                            translationY = if (listState.firstVisibleItemIndex == 0) {
                                -listState.firstVisibleItemScrollOffset.toFloat()
                            } else {
                                -browseHeroHeightPx
                            }
                        },
                )
            }

        }
    }
}

private suspend fun LazyListState.maybeTriggerBrowseLoadMore(
    itemCount: Int,
    isLoading: () -> Boolean,
    onLoadMore: () -> Unit,
) {
    snapshotFlow { layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .distinctUntilChanged()
        .collect { lastVisibleIndex: Int? ->
            val loading = isLoading()
            if (lastVisibleIndex != null && !loading && lastVisibleIndex >= itemCount - BrowseLoadMoreBuffer) {
                onLoadMore()
            }
        }
}

private fun openTrackingItem(
    appRouter: AppRouter,
    discoverViewModel: DiscoverViewModel,
    availableServices: List<ScrobblerService>,
    item: ContentListModel,
    sharedElementKey: String? = null,
    onNavigateToDetails: ((DetailsOrigin, String?) -> Unit)? = null,
) : Boolean {
    val serviceName = item.manga.source.name.removePrefix("TRACKING_")
    val trackingService = availableServices.find { it.name == serviceName } ?: return false
    if (discoverViewModel.supportsDetails(trackingService)) {
        if (onNavigateToDetails != null) {
            onNavigateToDetails(
                DetailsOrigin.TrackingItem(
                    serviceId = trackingService.id.toString(),
                    remoteId = item.manga.id,
                    url = item.manga.publicUrl,
                ),
                sharedElementKey,
            )
        } else {
            appRouter.openTrackingSiteDetails(trackingService, item.manga.id, item.manga.publicUrl)
        }
        return true
    } else {
        val url = item.manga.url ?: item.manga.publicUrl
        if (!url.isNullOrBlank()) {
            appRouter.openExternalBrowser(url)
            return true
        }
    }
    return false
}

@Composable
private fun BrowseHeroBlock(
    title: String,
    heroItems: List<ContentListModel>,
    activeService: ScrobblerService?,
    availableServices: List<ScrobblerService>,
    isLoadingOnly: Boolean,
    topContentInset: androidx.compose.ui.unit.Dp,
    settings: AppSettings,
    onSelectService: (ScrobblerService) -> Unit,
    onOpenSchedule: (() -> Unit)? = null,
    onHeroItemClick: (ContentListModel, String) -> Unit,
    sharedElementKeyForItem: (ContentListModel, Int) -> String,
    modifier: Modifier = Modifier,
) {
    if (heroItems.isNotEmpty()) {
        DiscoverHeroCarousel(
            title = title,
            items = heroItems,
            activeService = activeService,
            availableServices = availableServices,
            onSelectService = onSelectService,
            onOpenSchedule = onOpenSchedule,
            onItemClick = { item, _, sharedElementKey -> onHeroItemClick(item, sharedElementKey) },
            topContentInset = topContentInset,
            detachedBottomContent = true,
            settings = settings,
            sharedElementKeyForItem = sharedElementKeyForItem,
            modifier = modifier,
        )
    } else {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(
                    topContentInset + discoverHeroHeight(
                        isLandscape = isLandscape,
                        detachedBottomContent = true,
                    ),
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(top = topContentInset + 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoadingOnly) {
                BrowseHeroSkeleton()
            }
        }
    }
}

@Composable
private fun SourcesQuickAccessSection(
    sources: List<ContentSourceItem>,
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    isGroupedByLanguage: Boolean,
    selectedSourceIds: Set<Long>,
    forceExpanded: Boolean = false,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_extension),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.explore_tab_sources),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = onManageClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.extension_management),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            var isExpanded by rememberSaveable(sources.size) { mutableStateOf(false) }
            val columns = remember(maxWidth, metrics, browseListMode) {
                calculateSourceGridColumns(
                    availableWidth = maxWidth,
                    metrics = metrics,
                    browseListMode = browseListMode,
                )
            }
            val collapsedRowCount = if (maxWidth < 520.dp) 5 else 4
            val collapsedVisibleCount = columns * collapsedRowCount
            val groupedSources = remember(sources, isGroupedByLanguage, context) {
                sources.toQuickAccessGroups(
                    isGroupedByLanguage = isGroupedByLanguage,
                    context = context,
                )
            }
            val effectiveExpanded = forceExpanded || isExpanded
            val visibleGroups = remember(groupedSources, collapsedVisibleCount, effectiveExpanded) {
                groupedSources.takeVisibleSourceGroups(
                    maxSources = if (effectiveExpanded) Int.MAX_VALUE else collapsedVisibleCount,
                )
            }
            val hasMoreSources = !forceExpanded && sources.size > collapsedVisibleCount

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleGroups.forEach { group ->
                    group.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 2.dp),
                        )
                    }
                    SourceQuickAccessGrid(
                        metrics = metrics,
                        browseListMode = browseListMode,
                        columns = columns,
                        sources = group.sources,
                        selectedSourceIds = selectedSourceIds,
                        onSourceClick = onSourceClick,
                        onSourceLongClick = onSourceLongClick,
                    )
                }
                if (hasMoreSources) {
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = if (effectiveExpanded) {
                                stringResource(R.string.show_less)
                            } else {
                                "${stringResource(R.string.show_more)} (${sources.size - collapsedVisibleCount})"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceQuickAccessGrid(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    columns: Int,
    sources: List<ContentSourceItem>,
    selectedSourceIds: Set<Long>,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
) {
    val rows = remember(sources, columns) { sources.chunked(columns) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        rows.forEach { rowSources ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
            ) {
                rowSources.forEach { source ->
                    Box(modifier = Modifier.weight(1f)) {
                        SourceQuickAccessCard(
                            metrics = metrics,
                            browseListMode = browseListMode,
                            source = source,
                            isSelected = source.id in selectedSourceIds,
                            onClick = { onSourceClick(source) },
                            onLongClick = { onSourceLongClick(source) },
                        )
                    }
                }
                repeat(columns - rowSources.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun LazyListScope.sourceQuickAccessItems(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    columns: Int,
    visibleGroups: List<SourceQuickAccessRows>,
    selectedSourceIds: Set<Long>,
    hasMoreSources: Boolean,
    isExpanded: Boolean,
    topBackgroundOverlap: androidx.compose.ui.unit.Dp,
    onToggleExpanded: () -> Unit,
    onManageClick: () -> Unit,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
) {
    item(key = "source_quick_access_header", contentType = "source_quick_access_header") {
        SourceQuickAccessHeader(
            onManageClick = onManageClick,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    start = CompactTopBarHorizontalPadding,
                    end = CompactTopBarHorizontalPadding,
                    top = topBackgroundOverlap,
                    bottom = 4.dp,
                ),
        )
    }
    visibleGroups.forEachIndexed { groupIndex, group ->
        group.title?.let { title ->
            item(
                key = "source_group_${groupIndex}_$title",
                contentType = "source_group_header",
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            start = SourceGridHorizontalPadding,
                            end = SourceGridHorizontalPadding,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                )
            }
        }
        val rows = group.rows
        itemsIndexed(
            items = rows,
            key = { rowIndex, rowSources ->
                val firstId = rowSources.firstOrNull()?.id ?: rowIndex.toLong()
                "source_row_${groupIndex}_${rowIndex}_$firstId"
            },
            contentType = { _, _ -> "source_row" },
        ) { rowIndex, rowSources ->
            SourceQuickAccessRow(
                metrics = metrics,
                browseListMode = browseListMode,
                columns = columns,
                sources = rowSources,
                selectedSourceIds = selectedSourceIds,
                onSourceClick = onSourceClick,
                onSourceLongClick = onSourceLongClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(
                        start = SourceGridHorizontalPadding,
                        end = SourceGridHorizontalPadding,
                        bottom = if (rowIndex == rows.lastIndex) 0.dp else metrics.gridSpacing,
                    ),
            )
        }
    }
    if (hasMoreSources) {
        item(key = "source_quick_access_more", contentType = "source_quick_access_more") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onToggleExpanded,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (isExpanded) {
                            stringResource(R.string.show_less)
                        } else {
                            stringResource(R.string.show_more)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceQuickAccessHeader(
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_extension),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.explore_tab_sources),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(
            onClick = onManageClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Text(
                text = stringResource(R.string.extension_management),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SourceQuickAccessRow(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    columns: Int,
    sources: List<ContentSourceItem>,
    selectedSourceIds: Set<Long>,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        sources.forEach { source ->
            Box(modifier = Modifier.weight(1f)) {
                SourceQuickAccessCard(
                    metrics = metrics,
                    browseListMode = browseListMode,
                    source = source,
                    isSelected = source.id in selectedSourceIds,
                    onClick = { onSourceClick(source) },
                    onLongClick = { onSourceLongClick(source) },
                )
            }
        }
        repeat(columns - sources.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceQuickAccessCard(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    source: ContentSourceItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val actualSource = source.source.mangaSource
    val title = actualSource.getTitle(context)
    val isGridCard = browseListMode == ListMode.GRID || browseListMode == ListMode.COMPACT_GRID
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val cardShape = RoundedCornerShape(
        when {
            expressive && isGridCard -> 20.dp
            expressive -> 18.dp
            isGridCard -> 14.dp
            else -> 12.dp
        },
    )
    val cardBackground = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.background
    }
    val iconShape = RoundedCornerShape(if (expressive) 14.dp else if (isGridCard) 14.dp else 12.dp)
    val iconBackground = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (expressive) 0.62f else if (isGridCard) 0.44f else 0.52f,
    )

    if (isGridCard) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(cardShape)
                .background(cardBackground)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(metrics.iconContainerSize)
                    .clip(iconShape)
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                ContentSourceResolvedIcon(
                    source = actualSource,
                    modifier = Modifier.size(metrics.iconSize),
                    styleResId = R.style.FaviconDrawable_SourceIcon,
                    throttleNetworkLoad = true,
                    contentDescription = title,
                )
                SourceAvailabilityBadge(
                    availability = source.source.availability,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                if (source.source.isPinned) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        tonalElevation = 1.dp,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pin_small),
                            contentDescription = stringResource(R.string.source_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(3.dp)
                                .size(10.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = metrics.titleTextSize,
                    lineHeight = (metrics.titleTextSize.value + 2f).sp,
                ),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(cardShape)
                .background(cardBackground)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(iconShape)
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                ContentSourceResolvedIcon(
                    source = actualSource,
                    modifier = Modifier.size(28.dp),
                    styleResId = R.style.FaviconDrawable_SourceIcon,
                    throttleNetworkLoad = true,
                    contentDescription = title,
                )
                SourceAvailabilityBadge(
                    availability = source.source.availability,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                if (source.source.isPinned) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        tonalElevation = 1.dp,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pin_small),
                            contentDescription = stringResource(R.string.source_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(2.dp)
                                .size(9.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (browseListMode == ListMode.DETAILED_LIST) 2.dp else 0.dp),
            ) {
                Text(
                    text = title,
                    style = if (browseListMode == ListMode.DETAILED_LIST) {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (browseListMode == ListMode.DETAILED_LIST) {
                    Text(
                        text = actualSource.getLocale()?.getDisplayName(Locale.getDefault()).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceAvailabilityBadge(
    availability: ContentSourceAvailability,
    modifier: Modifier = Modifier,
) {
    if (availability != ContentSourceAvailability.EMPTY) {
        return
    }
    Surface(
        modifier = modifier.padding(3.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.error,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = stringResource(R.string.source_empty_badge_short),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> {
    return if (id in this) this - id else this + id
}

@Composable
private fun sourceTypeAccent(contentType: ContentType): Color = when (contentType) {
    ContentType.VIDEO -> MaterialTheme.colorScheme.tertiary
    ContentType.NOVEL -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}


private fun List<ContentSourceItem>.toQuickAccessGroups(
    isGroupedByLanguage: Boolean,
    context: android.content.Context,
): List<SourceQuickAccessGroup> {
    if (isEmpty()) {
        return emptyList()
    }
    if (!isGroupedByLanguage) {
        return listOf(SourceQuickAccessGroup(title = null, sources = this))
    }
    val result = ArrayList<SourceQuickAccessGroup>()
    val (pinned, unpinned) = partition { it.source.isPinned }
    if (pinned.isNotEmpty()) {
        result += SourceQuickAccessGroup(
            title = context.getString(R.string.source_pinned),
            sources = pinned,
        )
    }
    val grouped = unpinned
        .groupBy { sourceItem ->
            sourceItem.source.mangaSource.getLocale()
                ?.getDisplayName(Locale.getDefault())
                ?.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
                ?: context.getString(R.string.other)
        }
        .toSortedMap()
    grouped.forEach { (language, sourcesInLanguage) ->
        if (sourcesInLanguage.isNotEmpty()) {
            result += SourceQuickAccessGroup(
                title = language,
                sources = sourcesInLanguage,
            )
        }
    }
    return result
}

private fun List<SourceQuickAccessGroup>.takeVisibleSourceGroups(
    maxSources: Int,
): List<SourceQuickAccessGroup> {
    if (maxSources == Int.MAX_VALUE) {
        return this
    }
    var remaining = maxSources
    val result = ArrayList<SourceQuickAccessGroup>(size)
    for (group in this) {
        if (remaining <= 0) break
        val visibleSources = group.sources.take(remaining)
        if (visibleSources.isNotEmpty()) {
            result += group.copy(sources = visibleSources)
            remaining -= visibleSources.size
        }
    }
    return result
}

@Composable
private fun TrackingCategoryRow(
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
private fun BrowsePopularHeader(
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
private fun BrowsePopularListItem(
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingCompactPoster(
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
private fun BrowseHeroSkeleton(
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
private fun BrowseSourcesSkeleton(
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
private fun BrowsePopularLoadingSection(
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

private fun buildExploreCoverRequest(
    context: android.content.Context,
    coverUrl: String?,
    content: org.skepsun.kototoro.parsers.model.Content,
    size: Int? = null,
    blurPercent: Int = 0,
    sharedMemoryCacheKey: String? = null,
    crossfadeEnabled: Boolean = true,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(normalizeExploreCoverUrl(coverUrl))
        .mangaExtra(content)
        .crossfade(crossfadeEnabled)
        .panoramaBlur(blurPercent)
    if (sharedMemoryCacheKey != null) {
        builder.memoryCacheKey(sharedMemoryCacheKey)
        builder.diskCacheKey(sharedMemoryCacheKey)
    }
    if (size != null) {
        builder.size(size)
    }
    return builder.build()
}

private fun normalizeExploreCoverUrl(url: String?): String? = when {
    url == null -> null
    url.startsWith("//") -> "https:$url"
    else -> url
}
