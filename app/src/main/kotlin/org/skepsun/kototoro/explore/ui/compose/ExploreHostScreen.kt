package org.skepsun.kototoro.explore.ui.compose


import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.VerticalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.clearFailedContentSourceIcons
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.compose.ScrollToTopEffect
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.discover.ui.DiscoverViewModel
import org.skepsun.kototoro.discover.ui.compose.discoverHeroHeight
import org.skepsun.kototoro.discover.ui.model.DiscoverCarouselRow
import org.skepsun.kototoro.explore.ui.ExploreViewModel
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

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

internal data class SourceQuickAccessGroup(
    val title: String?,
    val sources: List<ContentSourceItem>,
)

internal data class SourceQuickAccessRows(
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
    return (12f + 4f * normalized).sp
}

internal fun calculateSourceGridColumns(
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
    val tvBoxRepositorySelection by exploreViewModel.tvBoxRepositorySelection.collectAsStateWithLifecycle()
    val discoverItems by discoverViewModel.content.collectAsStateWithLifecycle()
    val isDiscoverLoading by discoverViewModel.isLoading.collectAsStateWithLifecycle()
    val availableServices by discoverViewModel.availableServices.collectAsStateWithLifecycle()
    val activeService by discoverViewModel.activeService.collectAsStateWithLifecycle()
    val query by discoverViewModel.query.collectAsStateWithLifecycle()
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    ScrollToTopEffect {
        listState.scrollToItem(0)
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
    val sourceGridStartPadding = contentPadding.calculateStartPadding(layoutDirection) + SourceGridHorizontalPadding
    val sourceGridEndPadding = contentPadding.calculateEndPadding(layoutDirection) + SourceGridHorizontalPadding
    var isSourcesExpanded by rememberSaveable(sources.size, browseListMode, isSourcesGroupedByLanguage) {
        mutableStateOf(false)
    }
    val sourceContentWidth = remember(
        configuration.screenWidthDp,
        sourceGridStartPadding,
        sourceGridEndPadding,
    ) {
        configuration.screenWidthDp.dp - sourceGridStartPadding - sourceGridEndPadding
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
                    startPadding = sourceGridStartPadding,
                    endPadding = sourceGridEndPadding,
                    hasMoreSources = hasMoreSources,
                    isExpanded = areSourcesExpanded,
                    topBackgroundOverlap = heroOverlapDp,
                    tvBoxRepositorySelection = tvBoxRepositorySelection,
                    onTvBoxRepositorySelected = exploreViewModel::selectTvBoxRepository,
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
                                .padding(
                                    start = sourceGridStartPadding,
                                    end = sourceGridEndPadding,
                                    bottom = 36.dp,
                                ),
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

