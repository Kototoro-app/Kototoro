package org.skepsun.kototoro.main.ui.compose

import android.app.Activity
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.LayoutDirection

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.widgets.BottomNavState
import org.skepsun.kototoro.core.ui.widgets.KototoroBottomNav
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.glass.LocalHazeState
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefs
import org.skepsun.kototoro.core.ui.glass.supportsRuntimeHaze
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.compose.ExploreSelectionTopBar
import org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.FoldableUtils
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.ui.suggestion.model.TrackingEntity
import org.skepsun.kototoro.search.ui.compose.SearchNavigation
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.compose.SearchRoute
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import org.skepsun.kototoro.core.ui.compose.LocalRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.HeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.LocalHeroReturnTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.heroTransitionTimestampMs
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import kotlinx.coroutines.delay
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarFilterRail
import org.skepsun.kototoro.main.ui.compose.FavoritesTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.RouteScopedTopBarOverrideState

@Immutable
private data class KototoroNavigationPrefs(
    val isFloating: Boolean,
)

@Immutable
private data class KototoroDisplayPrefs(
    val activeSourcePresetId: Long,
    val listMode: ListMode,
    val browseListMode: ListMode,
    val gridSize: Int,
    val cornerRadius: Int,
    val isBrowseTrackingRecommendationsEnabled: Boolean,
    val isBrowseMoreTrackingRecommendationsEnabled: Boolean,
)

@Immutable
private data class KototoroFilterVisibilityPrefs(
    val isLanguagePresetFilterVisible: Boolean,
    val isContentTypeFilterVisible: Boolean,
    val isSourceTagFilterVisible: Boolean,
)

private fun routeOwnerKeyForDestination(
    destination: androidx.navigation.NavDestination?,
): String? = when {
    destination?.hasRoute<DiscoverRoute>() == true -> "discover"
    destination?.hasRoute<HistoryRoute>() == true -> "history"
    destination?.hasRoute<FavoritesRoute>() == true -> "favorites"
    destination?.hasRoute<ExploreRoute>() == true -> "explore"
    destination?.hasRoute<FeedRoute>() == true -> "feed"
    destination?.hasRoute<LocalRoute>() == true -> "local"
    destination?.hasRoute<SuggestionsRoute>() == true -> "suggestions"
    destination?.hasRoute<UpdatedRoute>() == true -> "updated"
    else -> null
}

private fun lerpFloat(
    start: Float,
    endInclusive: Float,
    fraction: Float,
): Float = start + (endInclusive - start) * fraction.coerceIn(0f, 1f)

@Composable
private fun BoxScope.ImmersiveEdgeGradient(
    height: androidx.compose.ui.unit.Dp,
    colors: List<Color>,
    stops: List<Float>? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(height)
            .drawWithCache {
                val brush = if (stops != null && stops.size == colors.size) {
                    Brush.verticalGradient(
                        colorStops = Array(colors.size) { index -> stops[index] to colors[index] },
                        startY = 0f,
                        endY = size.height,
                    )
                } else {
                    Brush.verticalGradient(
                        colors = colors,
                        startY = 0f,
                        endY = size.height,
                    )
                }
                onDrawBehind {
                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                    )
                }
            },
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KototoroApp(
    appSettings: AppSettings,
    navStateFlow: StateFlow<BottomNavState>,
    pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper,
    query: String = "",
    suggestions: List<SearchSuggestionItem> = emptyList(),
    onQueryChanged: (String) -> Unit = {},
    onSearch: (String) -> Unit = {},
    initialSearchKind: SearchKind = SearchKind.SIMPLE,
    initialSearchSourceTypes: Set<SourceType> = emptySet(),
    initialSearchContentKinds: Set<SearchContentKind> = emptySet(),
    onSearchWithOptions: (
        query: String,
        kind: SearchKind,
        sourceTypes: Set<SourceType>,
        contentKinds: Set<SearchContentKind>,
        advancedQuery: AdvancedSearchParams?,
        pinnedOnly: Boolean,
        hideEmpty: Boolean,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onSearchOverlaySourceTypesChange: (Set<SourceType>) -> Unit = {},
    onSearchOverlayContentKindsChange: (Set<SearchContentKind>) -> Unit = {},
    onSearchOverlayDismiss: () -> Unit = {},
    onContentSuggestionClick: (Content) -> Unit = {},
    onTrackingEntitySuggestionClick: (TrackingEntity) -> Unit = {},
    onTagSuggestionClick: (ContentTag) -> Unit = {},
    onSourceSuggestionClick: (ContentSource) -> Unit = {},
    onAuthorSuggestionClick: (String) -> Unit = {},
    onDeleteQuery: (String) -> Unit = {},
    onVoiceInput: () -> Unit = {},
    onOpenListOptions: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSourceSettingsClick: () -> Unit = {},
    onManageSourcesClick: () -> Unit = onSourceSettingsClick,
    onTrackingAccountsClick: () -> Unit = {},
    isAppUpdateAvailable: Boolean = false,
    onAppUpdateClick: () -> Unit = {},
    isIncognitoModeEnabled: Boolean = false,
    onIncognitoToggle: () -> Unit = {},
    isLanguagePresetFilterVisible: Boolean = false,
    languagePresetEntries: List<SourcePreset> = emptyList(),
    onLanguagePresetSelected: (Long) -> Unit = {},
    onManageLanguagePresets: () -> Unit = {},
    selectedContentType: ContentType? = null,
    enabledContentTypes: Set<ContentType> = setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
    isContentTypeFilterVisible: Boolean = true,
    onContentTypeSelected: (ContentType?) -> Unit = {},
    selectedSourceTags: Set<SourceTag> = emptySet(),
    sourceTagEntries: List<SourceTag> = SourceTag.quickFilterEntries,
    enabledSourceTags: Set<SourceTag> = sourceTagEntries.toSet(),
    isSourceTagFilterVisible: Boolean = true,
    onSourceTagFilterClick: (android.view.View?) -> Boolean = { false },
    onSourceTagSelected: (SourceTag?) -> Unit = {},
    onTopBarHeightChanged: (Int) -> Unit = {},
    onBottomNavHeightChanged: (Int) -> Unit = {},
    onContentInsetsChanged: (Int, Int) -> Unit = { _, _ -> },
    onNavDestinationChanged: (Int) -> Unit = {},
    pendingSearchNavigation: SearchNavigationRequest? = null,
    onSearchNavigationHandled: () -> Unit = {},
    isResumeEnabled: Boolean = false,
    onResumeClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val navigationPrefs by appSettings.observeAsState(
        AppSettings.KEY_NAV_FLOATING,
    ) {
        KototoroNavigationPrefs(
            isFloating = isNavFloating,
        )
    }
    val displayPrefs by appSettings.observeAsState(
        AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
        AppSettings.KEY_LIST_MODE,
        AppSettings.KEY_LIST_MODE_BROWSE,
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_POPUP_RADIUS,
        AppSettings.KEY_BROWSE_TRACKING_RECOMMENDATIONS,
        AppSettings.KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS,
    ) {
        KototoroDisplayPrefs(
            activeSourcePresetId = activeSourcePresetId,
            listMode = listMode,
            browseListMode = browseListMode,
            gridSize = gridSize,
            cornerRadius = cornerRadius,
            isBrowseTrackingRecommendationsEnabled = isBrowseTrackingRecommendationsEnabled,
            isBrowseMoreTrackingRecommendationsEnabled = isBrowseMoreTrackingRecommendationsEnabled,
        )
    }
    val filterVisibilityPrefs by appSettings.observeAsState(
        AppSettings.KEY_SHOW_LANGUAGE_PRESET_FILTER,
        AppSettings.KEY_SHOW_CONTENT_TYPE_FILTER,
        AppSettings.KEY_SHOW_SOURCE_TAG_FILTER,
    ) {
        KototoroFilterVisibilityPrefs(
            isLanguagePresetFilterVisible = isShowLanguagePresetFilter,
            isContentTypeFilterVisible = isShowContentTypeFilter,
            isSourceTagFilterVisible = isShowSourceTagFilter,
        )
    }
    val isSharedElementTransitionsEnabled by appSettings.observeAsState(
        AppSettings.KEY_SHARED_ELEMENT_TRANSITIONS,
    ) {
        isSharedElementTransitionsEnabled
    }
    val isNavBarPinned by appSettings.observeAsState(AppSettings.KEY_NAV_PINNED) { isNavBarPinned }
    val isFloating = navigationPrefs.isFloating
    val activeSourcePresetId = displayPrefs.activeSourcePresetId
    val listMode = displayPrefs.listMode
    val browseListMode = displayPrefs.browseListMode
    val gridSize = displayPrefs.gridSize
    val cornerRadius = displayPrefs.cornerRadius
    val isBrowseTrackingRecommendationsEnabled = displayPrefs.isBrowseTrackingRecommendationsEnabled
    val isBrowseMoreTrackingRecommendationsEnabled = displayPrefs.isBrowseMoreTrackingRecommendationsEnabled
    val tabletUiMode by appSettings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }
    val isLandscapeNavigation = remember(
        context,
        configuration.orientation,
        configuration.screenWidthDp,
        tabletUiMode,
    ) {
        FoldableUtils.shouldUseTabletLayout(context, appSettings, configuration)
    }
    val isLanguagePresetFilterVisibleSetting = filterVisibilityPrefs.isLanguagePresetFilterVisible
    val isContentTypeFilterVisibleSetting = filterVisibilityPrefs.isContentTypeFilterVisible
    val isSourceTagFilterVisibleSetting = filterVisibilityPrefs.isSourceTagFilterVisible
    
    val effectiveLanguagePresetFilterVisible = isLanguagePresetFilterVisible && isLanguagePresetFilterVisibleSetting
    val effectiveContentTypeFilterVisible = isContentTypeFilterVisible && isContentTypeFilterVisibleSetting
    val effectiveSourceTagFilterVisible = isSourceTagFilterVisible && isSourceTagFilterVisibleSetting

    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomNavHeightPx by remember { mutableIntStateOf(0) }
    var topFilterRailHeightPx by remember { mutableIntStateOf(0) }
    var topBarOffset by remember { mutableFloatStateOf(0f) }
    var bottomNavOffset by remember { mutableFloatStateOf(0f) }
    var topFilterRailOffset by remember { mutableFloatStateOf(0f) }
    var isLandscapeRailInteracting by remember { mutableStateOf(false) }
    var isSearchOverlayVisible by rememberSaveable { mutableStateOf(false) }
    var isSearchOverlayMounted by rememberSaveable { mutableStateOf(false) }
    var searchOverlayInitialQuery by rememberSaveable { mutableStateOf("") }
    var isSearchOverlayQueryCommitted by rememberSaveable { mutableStateOf(false) }
    var isDetailsChromeTransitionPending by rememberSaveable { mutableStateOf(false) }
    val routeTopBarOverrideStates = remember { mutableStateMapOf<String, TopBarOverrideState>() }
    var globalTopBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var contextualMenuActions by remember { mutableStateOf<List<KototoroTopBarMenuAction>>(emptyList()) }
    var offsetDestinationRoute by remember { mutableStateOf<String?>(null) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarHeightPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().roundToPx()
    }
    val navigationBarHeightPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().roundToPx()
    }
    val nestedScrollConnection = remember(
        isNavBarPinned,
        isLandscapeNavigation,
        isLandscapeRailInteracting,
        topBarHeightPx,
        bottomNavHeightPx,
        isSearchOverlayMounted,
    ) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (isSearchOverlayMounted || isLandscapeRailInteracting) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                val dy = available.y
                if (!isNavBarPinned && dy != 0f) {
                    topBarOffset = (topBarOffset + dy).coerceIn(-topBarHeightPx.toFloat(), 0f)
                    bottomNavOffset = if (isLandscapeNavigation) {
                        0f
                    } else {
                        (bottomNavOffset - dy).coerceIn(0f, bottomNavHeightPx.toFloat())
                    }
                } else if (isNavBarPinned) {
                    topBarOffset = 0f
                    bottomNavOffset = 0f
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    LaunchedEffect(isSearchOverlayMounted) {
        if (isSearchOverlayMounted) {
            topBarOffset = 0f
            bottomNavOffset = 0f
            topFilterRailOffset = 0f
        }
    }

    LaunchedEffect(isLandscapeNavigation) {
        if (isLandscapeNavigation) {
            bottomNavOffset = 0f
        }
    }

    val navController = rememberNavController()
    fun navigateToBottomNavItem(
        itemId: Int,
        restoreState: Boolean = true,
    ) {
        val route = routeForBottomNavItem(itemId)
        if (!navController.currentDestination.isBottomNavRoute(itemId)) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = false
                    saveState = restoreState
                }
                launchSingleTop = true
                this.restoreState = restoreState
            }
        }
    }
    fun navigateFromBottomNav(itemId: Int) {
        val shouldReturnHome = navController.currentBackStackEntry
            ?.savedStateHandle
            ?.get<Boolean>(RETURN_HOME_ON_BACK_KEY) == true
        if (itemId == org.skepsun.kototoro.R.id.nav_home && shouldReturnHome) {
            navController.currentBackStackEntry?.savedStateHandle?.set(RETURN_HOME_ON_BACK_KEY, false)
            navigateToBottomNavItem(itemId, restoreState = false)
        } else {
            navigateToBottomNavItem(itemId)
        }
    }
    val mainNavItems by appSettings.observeAsState(AppSettings.KEY_NAV_MAIN) { mainNavItems }
    val startDestination = remember(mainNavItems) {
        mainNavItems.firstOrNull()?.let { routeForBottomNavItem(it.id) } ?: HomeRoute
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentDestinationRoute = currentDestination?.route
    val isSearchRoute = currentDestination?.hasRoute<SearchRoute>() == true
    val isDetailsRoute = currentDestination?.hasRoute<DetailsRoute>() == true
    val shouldShowChrome = !isSearchRoute && !isDetailsRoute
    val currentTopBarOwnerKey = routeOwnerKeyForDestination(currentDestination)
    var lastChromeTopBarOwnerKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentTopBarOwnerKey) {
        if (currentTopBarOwnerKey != null) {
            lastChromeTopBarOwnerKey = currentTopBarOwnerKey
        }
    }
    val chromeTopBarOwnerKey = currentTopBarOwnerKey ?: if (isDetailsRoute && isDetailsChromeTransitionPending) {
        lastChromeTopBarOwnerKey
    } else {
        null
    }
    val shouldReserveChromeInsets = shouldShowChrome || (isDetailsRoute && isDetailsChromeTransitionPending)
    val isChromeOffsetFromCurrentDestination = offsetDestinationRoute == currentDestinationRoute
    val effectiveTopBarOffset = if (isChromeOffsetFromCurrentDestination) topBarOffset else 0f
    val effectiveBottomNavOffset = if (isChromeOffsetFromCurrentDestination) bottomNavOffset else 0f
    LaunchedEffect(currentDestinationRoute, currentTopBarOwnerKey) {
        if (currentDestinationRoute != null && !isDetailsRoute && !isSearchRoute) {
            topBarOffset = 0f
            bottomNavOffset = 0f
            topFilterRailOffset = 0f
            offsetDestinationRoute = currentDestinationRoute
        }
    }
    var isChromeVisible by rememberSaveable { mutableStateOf(shouldShowChrome && !isDetailsRoute) }
    var lastResolvedWasDetailsRoute by rememberSaveable { mutableStateOf(isDetailsRoute) }
    var lastHeroTransitionStartedAtMs by remember { mutableLongStateOf(0L) }
    var heroTransitionPhase by rememberSaveable { mutableStateOf(HeroTransitionPhase.Idle) }
    LaunchedEffect(currentDestination, shouldShowChrome, isDetailsRoute, isDetailsChromeTransitionPending) {
        if (currentDestination == null) {
            return@LaunchedEffect
        }
        when {
            isDetailsRoute -> {
                lastResolvedWasDetailsRoute = true
                if (!isDetailsChromeTransitionPending) {
                    isChromeVisible = false
                    return@LaunchedEffect
                }
                isChromeVisible = true
                delay(220)
                isChromeVisible = false
                isDetailsChromeTransitionPending = false
            }
            isDetailsChromeTransitionPending && heroTransitionPhase == HeroTransitionPhase.EnteringDetails -> {
                isChromeVisible = false
                lastResolvedWasDetailsRoute = false
            }
            !shouldShowChrome -> {
                isChromeVisible = false
                lastResolvedWasDetailsRoute = false
                isDetailsChromeTransitionPending = false
            }
            lastResolvedWasDetailsRoute -> {
                // Wait until the details pop animation settles before restoring the main chrome.
                isChromeVisible = false
                delay(220)
                isChromeVisible = true
                lastResolvedWasDetailsRoute = false
                isDetailsChromeTransitionPending = false
            }
            else -> {
                isChromeVisible = true
                lastResolvedWasDetailsRoute = false
                isDetailsChromeTransitionPending = false
            }
        }
    }
    val heroTransitionInProgress by produceState(
        initialValue = false,
        isDetailsChromeTransitionPending,
        isDetailsRoute,
        lastHeroTransitionStartedAtMs,
    ) {
        if (!isDetailsRoute && !isDetailsChromeTransitionPending) {
            value = false
            return@produceState
        }
        if (lastHeroTransitionStartedAtMs == 0L) {
            value = isDetailsChromeTransitionPending
            return@produceState
        }
        value = isDetailsChromeTransitionPending || isDetailsRoute
        val elapsed = heroTransitionTimestampMs() - lastHeroTransitionStartedAtMs
        if (elapsed < 320L) {
            value = true
            delay(320L - elapsed)
        }
        value = false
    }
    val heroReturnTransitionInProgress =
        heroTransitionInProgress && heroTransitionPhase == HeroTransitionPhase.ReturningFromDetails
    LaunchedEffect(heroTransitionInProgress) {
        if (!heroTransitionInProgress && heroTransitionPhase != HeroTransitionPhase.Idle) {
            heroTransitionPhase = HeroTransitionPhase.Idle
        }
    }
    val scrollAlpha = if (!isChromeVisible) 0f else {
        val maxCollapse = topBarHeightPx.toFloat()
        if (maxCollapse <= 0f) 1f
        else (1f + effectiveTopBarOffset / maxCollapse).coerceIn(0f, 1f)
    }
    val showBrowseSourceSettingsEntry = currentDestination?.let {
        it.hasRoute<ExploreRoute>() || it.hasRoute<DiscoverRoute>()
    } == true
    val resolvedTopBarOverrideState = chromeTopBarOwnerKey
        ?.let(routeTopBarOverrideStates::get)
        ?: globalTopBarOverrideState
    val layeredTopBarOverrideState = when (resolvedTopBarOverrideState) {
        is LayeredTopBarOverrideState -> resolvedTopBarOverrideState
        is FavoritesTopBarOverrideState -> LayeredTopBarOverrideState(
            tabsState = resolvedTopBarOverrideState.tabsState,
            filterRailState = resolvedTopBarOverrideState.filterRailState,
            contextualOverrideState = resolvedTopBarOverrideState.contextualOverrideState,
        )
        else -> null
    }
    val topTabsOverrideState = layeredTopBarOverrideState?.tabsState ?: (resolvedTopBarOverrideState as? CompactTabsTopBarOverrideState)
    val topFilterRailOverrideState = layeredTopBarOverrideState?.filterRailState
    val effectiveTopBarOverrideState = if (layeredTopBarOverrideState != null) {
        layeredTopBarOverrideState.contextualOverrideState
    } else {
        resolvedTopBarOverrideState
    }
    val shouldKeepFavoriteTabsVisible = !isNavBarPinned &&
        chromeTopBarOwnerKey == "favorites" &&
        !isDetailsChromeTransitionPending &&
        topTabsOverrideState != null &&
        scrollAlpha < 0.98f
    val isEnteringDetailsTransition =
        isDetailsChromeTransitionPending && heroTransitionPhase == HeroTransitionPhase.EnteringDetails
    val effectiveChromeAlphaTarget = if (isEnteringDetailsTransition) {
        0f
    } else if (shouldKeepFavoriteTabsVisible) {
        1f
    } else {
        scrollAlpha
    }
    val effectiveCompactTabsTopBarOffset = if (shouldKeepFavoriteTabsVisible) {
        0f
    } else {
        effectiveTopBarOffset
    }
    val animatedChromeAlpha by animateFloatAsState(
        targetValue = effectiveChromeAlphaTarget,
        animationSpec = tween(durationMillis = 120),
        label = "chrome_alpha",
    )
    val chromeAlpha = if (isEnteringDetailsTransition) 0f else animatedChromeAlpha
    val isHomeRoute = currentDestination?.hasRoute<HomeRoute>() == true
    val supportsDisplayModeMenu = currentDestination?.let {
        it.hasRoute<ExploreRoute>() ||
            it.hasRoute<DiscoverRoute>() ||
            it.hasRoute<HomeRoute>() ||
            it.hasRoute<HistoryRoute>() ||
            it.hasRoute<FavoritesRoute>() ||
            it.hasRoute<LocalRoute>() ||
            it.hasRoute<SuggestionsRoute>() ||
            it.hasRoute<UpdatedRoute>()
    } == true
    val supportsGridSizeSlider = currentDestination?.let {
        it.hasRoute<HomeRoute>() ||
            it.hasRoute<DiscoverRoute>() ||
            it.hasRoute<ExploreRoute>() ||
            it.hasRoute<FeedRoute>() ||
            it.hasRoute<HistoryRoute>() ||
            it.hasRoute<FavoritesRoute>() ||
            it.hasRoute<LocalRoute>() ||
            it.hasRoute<SuggestionsRoute>() ||
            it.hasRoute<UpdatedRoute>()
    } == true

    LaunchedEffect(currentDestination) {
        val mappedId = when {
            currentDestination?.hasRoute<HomeRoute>() == true -> org.skepsun.kototoro.R.id.nav_home
            currentDestination?.hasRoute<HistoryRoute>() == true -> org.skepsun.kototoro.R.id.nav_history
            currentDestination?.hasRoute<FavoritesRoute>() == true -> org.skepsun.kototoro.R.id.nav_favorites
            currentDestination?.hasRoute<ExploreRoute>() == true -> org.skepsun.kototoro.R.id.nav_explore
            currentDestination?.hasRoute<DiscoverRoute>() == true -> org.skepsun.kototoro.R.id.nav_discover
            currentDestination?.hasRoute<FeedRoute>() == true -> org.skepsun.kototoro.R.id.nav_feed
            currentDestination?.hasRoute<LocalRoute>() == true -> org.skepsun.kototoro.R.id.nav_local
            currentDestination?.hasRoute<SuggestionsRoute>() == true -> org.skepsun.kototoro.R.id.nav_suggestions
            currentDestination?.hasRoute<BookmarksRoute>() == true -> org.skepsun.kototoro.R.id.nav_bookmarks
            currentDestination?.hasRoute<UpdatedRoute>() == true -> org.skepsun.kototoro.R.id.nav_updated
            else -> -1
        }
        if (mappedId != -1) {
            onNavDestinationChanged(mappedId)
        }
    }

    val reservedTopBarHeightPx = maxOf(
        topBarHeightPx,
        statusBarHeightPx + with(density) { 44.dp.roundToPx() },
    )
    val maxCollapsePx = (reservedTopBarHeightPx - statusBarHeightPx).coerceAtLeast(0)
    val reservedTopFilterRailHeightPx = when {
        topFilterRailHeightPx > 0 -> topFilterRailHeightPx
        else -> with(density) { 34.dp.roundToPx() }
    }
    val visibleTopFilterInsetPx = if (shouldReserveChromeInsets && topFilterRailOverrideState != null) {
        val gapPx = with(density) { 8.dp.roundToPx() }
        reservedTopFilterRailHeightPx + gapPx
    } else {
        0
    }
    val contentTopInsetPx = if (shouldReserveChromeInsets) {
        (reservedTopBarHeightPx + effectiveTopBarOffset).toInt()
            .coerceIn(maxCollapsePx, reservedTopBarHeightPx) + visibleTopFilterInsetPx
    } else {
        0
    }
    val displayCutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val displayCutoutStartDp = displayCutoutPadding.calculateLeftPadding(LayoutDirection.Ltr)
    val displayCutoutEndDp = displayCutoutPadding.calculateRightPadding(LayoutDirection.Ltr)
    val extraPinnedBottomInsetPx = with(density) {
        if (isNavBarPinned && !isFloating) 12.dp.roundToPx() else 0
    }
    val visibleBottomNavInsetPx = (bottomNavHeightPx - effectiveBottomNavOffset).coerceAtLeast(0f).toInt() + extraPinnedBottomInsetPx
    val contentBottomInsetPx = if (!shouldReserveChromeInsets || isLandscapeNavigation) {
        0
    } else {
        maxOf(visibleBottomNavInsetPx, navigationBarHeightPx)
    }
    val visibleStartInsetDp = with(density) {
        if (isLandscapeNavigation) {
            bottomNavHeightPx.toFloat().toDp()
        } else {
            0.dp
        }
    }

    LaunchedEffect(topFilterRailOverrideState) {
        if (topFilterRailOverrideState == null) {
            topFilterRailOffset = 0f
            topFilterRailHeightPx = 0
        }
    }

    LaunchedEffect(contentTopInsetPx, contentBottomInsetPx) {
        onContentInsetsChanged(contentTopInsetPx, contentBottomInsetPx)
    }
    val contentPadding = remember(contentTopInsetPx, contentBottomInsetPx, density) {
        with(density) {
            androidx.compose.foundation.layout.PaddingValues(
                top = contentTopInsetPx.toDp(),
                bottom = contentBottomInsetPx.toDp()
            )
        }
    }


    KototoroTheme(cornerRadius = cornerRadius) {
        val hazeState = remember { HazeState() }
        val transitionHazeState = remember { HazeState() }
        val glassPrefs = rememberGlassPrefs(appSettings)
        val railAnimationFactor = rememberRailAnimationFactor(appSettings)
        val useRuntimeHaze = remember { supportsRuntimeHaze() }
        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalGlassPrefs provides glassPrefs,
            LocalRailAnimationFactor provides railAnimationFactor,
        ) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(if (useRuntimeHaze) Modifier.hazeSource(transitionHazeState) else Modifier)
                .nestedScroll(nestedScrollConnection)
                .padding(start = displayCutoutStartDp, end = displayCutoutEndDp)) {
                SharedTransitionLayout {
                    CompositionLocalProvider(
                        LocalHeroTransitionInProgress provides false,
                        LocalHeroReturnTransitionInProgress provides false,
                        LocalHeroTransitionPhase provides HeroTransitionPhase.Idle,
                        LocalSharedTransitionScope provides if (isSharedElementTransitionsEnabled) {
                            this@SharedTransitionLayout
                        } else {
                            null
                        },
                    ) {
                        AppNavGraph(
                            navController = navController,
                            isLandscapeNavigation = isLandscapeNavigation,
                            startDestination = startDestination,
                            contentPadding = contentPadding,
                            bottomBarOffsetPx = effectiveBottomNavOffset,
                            bottomBarHeightPx = bottomNavHeightPx,
                            pageSaveHelper = pageSaveHelper,
                            onDetailsTransitionRequested = {
                                isDetailsChromeTransitionPending = true
                                heroTransitionPhase = HeroTransitionPhase.EnteringDetails
                                lastHeroTransitionStartedAtMs = heroTransitionTimestampMs()
                            },
                            onDetailsReturnTransitionRequested = {
                                isDetailsChromeTransitionPending = true
                                heroTransitionPhase = HeroTransitionPhase.ReturningFromDetails
                                lastHeroTransitionStartedAtMs = heroTransitionTimestampMs()
                            },
                            onExploreSourceSelectionTopBarChanged = { overrideState ->
                                when (overrideState) {
                                    is RouteScopedTopBarOverrideState -> {
                                        val ownerRoute = overrideState.ownerRoute
                                        val state = overrideState.state
                                        if (state == null) {
                                            if (ownerRoute in routeTopBarOverrideStates) {
                                                routeTopBarOverrideStates.remove(ownerRoute)
                                            }
                                        } else if (routeTopBarOverrideStates[ownerRoute] !== state) {
                                            routeTopBarOverrideStates[ownerRoute] = state
                                        }
                                    }
                                    else -> {
                                        if (globalTopBarOverrideState !== overrideState) {
                                            globalTopBarOverrideState = overrideState
                                        }
                                    }
                                }
                            },
                            onContextualMenuActionsChanged = { contextualMenuActions = it },
                            onOpenSearch = { request ->
                                val route = SearchNavigation.createRoute(request)
                                if (isSearchRoute) {
                                    navController.navigate(route) {
                                        popUpTo<SearchRoute> { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (useRuntimeHaze) Modifier.hazeSource(hazeState) else Modifier)
                        )
                    }
                }

                val immersiveStrength = ((LocalGlassPrefs.current?.immersiveStrengthPercent ?: 65).coerceIn(0, 100)) / 100f
                val isDarkTheme = isSystemInDarkTheme()
                val immersiveBaseColor = if (isDarkTheme) Color.Black else Color.White
                val immersiveTransparent = Color.Transparent
                val topImmersiveOverflowPx = with(density) { 6.dp.roundToPx() }
                val topImmersiveHeight = with(density) {
                    (
                        statusBarHeightPx +
                            (topBarHeightPx * 0.72f).toInt() +
                            if (shouldShowChrome && topFilterRailOverrideState != null) topFilterRailHeightPx else 0 +
                            topImmersiveOverflowPx
                        )
                        .coerceAtLeast(statusBarHeightPx + topImmersiveOverflowPx)
                        .toDp()
                }
                val bottomImmersiveHeight = with(density) {
                    (
                        (navigationBarHeightPx / 2) +
                            if (!isLandscapeNavigation && shouldShowChrome) bottomNavHeightPx else 0
                        )
                        .coerceAtLeast(if (!isLandscapeNavigation && shouldShowChrome) bottomNavHeightPx else navigationBarHeightPx / 2)
                        .toDp()
                }

                if (!isDetailsRoute) {
                    ImmersiveEdgeGradient(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                        height = topImmersiveHeight,
                        colors = listOf(
                            immersiveBaseColor.copy(alpha = lerpFloat(0.72f, 0.98f, immersiveStrength)),
                            immersiveBaseColor.copy(alpha = lerpFloat(0.56f, 0.82f, immersiveStrength)),
                            immersiveBaseColor.copy(alpha = lerpFloat(0.32f, 0.52f, immersiveStrength)),
                            immersiveBaseColor.copy(alpha = lerpFloat(0.12f, 0.22f, immersiveStrength)),
                            immersiveTransparent,
                        ),
                        stops = listOf(0f, 0.38f, 0.72f, 0.92f, 1f),
                    )

                    ImmersiveEdgeGradient(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        height = bottomImmersiveHeight,
                        colors = listOf(
                            immersiveTransparent,
                            immersiveBaseColor.copy(alpha = lerpFloat(0.14f, 0.24f, immersiveStrength)),
                            immersiveBaseColor.copy(alpha = lerpFloat(0.34f, 0.54f, immersiveStrength)),
                            immersiveBaseColor.copy(alpha = lerpFloat(0.60f, 0.90f, immersiveStrength)),
                        ),
                        stops = listOf(0f, 0.22f, 0.62f, 1f),
                    )
                }

                if (shouldShowChrome || isChromeVisible || chromeAlpha > 0f) {
                    if (effectiveTopBarOverrideState != null && effectiveTopBarOverrideState !is CompactTabsTopBarOverrideState) {
                        when (val overrideState = effectiveTopBarOverrideState) {
                            is ExploreSourceSelectionTopBarState -> {
                                ExploreSelectionTopBar(
                                    selectedCount = overrideState.selectedCount,
                                    isSingleSelection = overrideState.isSingleSelection,
                                    canPin = overrideState.canPin,
                                    canUnpin = overrideState.canUnpin,
                                    canDisable = overrideState.canDisable,
                                    canDelete = overrideState.canDelete,
                                    onClearSelection = overrideState.onClearSelection,
                                    onSettings = overrideState.onSettings,
                                    onDisable = overrideState.onDisable,
                                    onDelete = overrideState.onDelete,
                                    onShortcut = overrideState.onShortcut,
                                    onPin = overrideState.onPin,
                                    onUnpin = overrideState.onUnpin,
                                    modifier = Modifier
                                        .align(if (isLandscapeNavigation) Alignment.TopStart else Alignment.TopCenter)
                                        .then(if (isLandscapeNavigation) Modifier.fillMaxWidth() else Modifier)
                                        .padding(start = visibleStartInsetDp)
                                        .offset { androidx.compose.ui.unit.IntOffset(0, effectiveTopBarOffset.toInt()) }
                                        .graphicsLayer { alpha = chromeAlpha }
                                        .onGloballyPositioned { coords ->
                                            val newHeight = coords.size.height
                                            if (topBarHeightPx != newHeight) {
                                                topBarHeightPx = newHeight
                                                onTopBarHeightChanged(newHeight)
                                            }
                                        },
                                )
                            }

                            is ContentSelectionTopBarOverrideState -> {
                                org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar(
                                    selectedCount = overrideState.selectedCount,
                                    isAllNonLocal = overrideState.isAllNonLocal,
                                    isSingleSelection = overrideState.isSingleSelection,
                                    showRemoveOption = overrideState.showRemoveOption,
                                    supportedActions = overrideState.supportedActions,
                                    allPinned = overrideState.allPinned,
                                    preferredInlineActions = overrideState.preferredInlineActions,
                                    removeActionIconRes = overrideState.removeActionIconRes,
                                    removeActionTitleRes = overrideState.removeActionTitleRes,
                                    onClearSelection = overrideState.onClearSelection,
                                    onActionClick = overrideState.onActionClick,
                                    modifier = Modifier
                                        .align(if (isLandscapeNavigation) Alignment.TopStart else Alignment.TopCenter)
                                        .then(if (isLandscapeNavigation) Modifier.fillMaxWidth() else Modifier)
                                        .padding(start = visibleStartInsetDp)
                                        .offset { androidx.compose.ui.unit.IntOffset(0, effectiveTopBarOffset.toInt()) }
                                        .graphicsLayer { alpha = chromeAlpha }
                                        .onGloballyPositioned { coords ->
                                            val newHeight = coords.size.height
                                            if (topBarHeightPx != newHeight) {
                                                topBarHeightPx = newHeight
                                                onTopBarHeightChanged(newHeight)
                                            }
                                        },
                                )
                            }

                            is CompactTabsTopBarOverrideState -> Unit
                            is FavoritesTopBarOverrideState -> Unit
                            is LayeredTopBarOverrideState -> Unit
                        }
                    } else {
                        KototoroTopBar(
                            query = query,
                            onSearchClick = {
                                searchOverlayInitialQuery = query
                                isSearchOverlayQueryCommitted = false
                                isSearchOverlayMounted = true
                                isSearchOverlayVisible = true
                            },
                            onOpenListOptions = onOpenListOptions,
                            onSettingsClick = onSettingsClick,
                            onSourceSettingsClick = onSourceSettingsClick,
                            onManageSourcesClick = onManageSourcesClick,
                            onTrackingAccountsClick = onTrackingAccountsClick,
                            isAppUpdateAvailable = isAppUpdateAvailable,
                            onAppUpdateClick = onAppUpdateClick,
                            isIncognitoModeEnabled = isIncognitoModeEnabled,
                            onIncognitoToggle = onIncognitoToggle,
                            isLanguagePresetFilterVisible = effectiveLanguagePresetFilterVisible,
                            languagePresetEntries = languagePresetEntries,
                            activeLanguagePresetId = activeSourcePresetId,
                            onLanguagePresetSelected = onLanguagePresetSelected,
                            onManageLanguagePresets = onManageLanguagePresets,
                            compactTabsState = topTabsOverrideState,
                            selectedContentType = selectedContentType,
                            enabledContentTypes = enabledContentTypes,
                            isContentTypeFilterVisible = effectiveContentTypeFilterVisible,
                            onContentTypeSelected = onContentTypeSelected,
                            selectedSourceTags = selectedSourceTags,
                            sourceTagEntries = sourceTagEntries,
                            enabledSourceTags = enabledSourceTags,
                            isSourceTagFilterVisible = effectiveSourceTagFilterVisible,
                            onSourceTagFilterClick = onSourceTagFilterClick,
                            onSourceTagSelected = onSourceTagSelected,
                            supportsDisplayModeMenu = supportsDisplayModeMenu,
                            currentListMode = when {
                                showBrowseSourceSettingsEntry -> browseListMode
                                isHomeRoute -> appSettings.homeListMode
                                else -> listMode
                            },
                            onListModeSelected = {
                                if (showBrowseSourceSettingsEntry) {
                                    appSettings.browseListMode = it
                                } else if (isHomeRoute) {
                                    appSettings.homeListMode = it
                                } else {
                                    appSettings.listMode = it
                                }
                            },
                            supportsGridSizeSlider = supportsGridSizeSlider,
                            gridSize = gridSize,
                            onGridSizeChange = { appSettings.gridSize = it },
                            isBrowseTrackingRecommendationsEnabled = if (showBrowseSourceSettingsEntry) {
                                isBrowseTrackingRecommendationsEnabled
                            } else {
                                null
                            },
                            onBrowseTrackingRecommendationsChange = if (showBrowseSourceSettingsEntry) {
                                { appSettings.isBrowseTrackingRecommendationsEnabled = it }
                            } else {
                                null
                            },
                            isBrowseMoreTrackingRecommendationsEnabled = if (showBrowseSourceSettingsEntry) {
                                isBrowseMoreTrackingRecommendationsEnabled
                            } else {
                                null
                            },
                            onBrowseMoreTrackingRecommendationsChange = if (showBrowseSourceSettingsEntry) {
                                { appSettings.isBrowseMoreTrackingRecommendationsEnabled = it }
                            } else {
                                null
                            },
                            showSourceSettingsEntry = showBrowseSourceSettingsEntry,
                            contextualMenuActions = contextualMenuActions,
                            forceCompactTabsExpanded = shouldKeepFavoriteTabsVisible,
                            modifier = Modifier
                                .align(if (isLandscapeNavigation) Alignment.TopStart else Alignment.TopCenter)
                                .then(if (isLandscapeNavigation) Modifier.fillMaxWidth() else Modifier)
                                .padding(start = visibleStartInsetDp)
                                .offset { androidx.compose.ui.unit.IntOffset(0, effectiveCompactTabsTopBarOffset.toInt()) }
                                .graphicsLayer { alpha = chromeAlpha }
                                .onGloballyPositioned { coords ->
                                    val newHeight = coords.size.height
                                    if (topBarHeightPx != newHeight) {
                                        topBarHeightPx = newHeight
                                        onTopBarHeightChanged(newHeight)
                                    }
                                },
                        )

                        topFilterRailOverrideState?.let { filterState ->
                            CompactTopBarFilterRail(
                                state = filterState,
                                modifier = Modifier
                                    .align(if (isLandscapeNavigation) Alignment.TopStart else Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(start = visibleStartInsetDp)
                                    .padding(horizontal = 12.dp)
                                    .offset {
                                        androidx.compose.ui.unit.IntOffset(
                                            0,
                                            effectiveTopBarOffset.toInt() + topBarHeightPx + with(density) { 8.dp.roundToPx() },
                                        )
                                    }
                                    .graphicsLayer { alpha = chromeAlpha }
                                    .onGloballyPositioned { coords ->
                                        val newHeight = coords.size.height
                                        if (topFilterRailHeightPx != newHeight) {
                                            topFilterRailHeightPx = newHeight
                                        }
                                    },
                            )
                        }
                    }

                    Box(
                                modifier = Modifier
                                    .align(if (isLandscapeNavigation) Alignment.CenterStart else Alignment.BottomCenter)
                                    .then(
                                        if (isLandscapeNavigation) {
                                            Modifier.pointerInteropFilter { event ->
                                                when (event.actionMasked) {
                                                    MotionEvent.ACTION_DOWN -> isLandscapeRailInteracting = true
                                                    MotionEvent.ACTION_UP,
                                                    MotionEvent.ACTION_CANCEL -> isLandscapeRailInteracting = false
                                                }
                                                false
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .offset {
                                        if (isLandscapeNavigation) {
                                    androidx.compose.ui.unit.IntOffset((-effectiveBottomNavOffset).toInt(), 0)
                                        } else {
                                    androidx.compose.ui.unit.IntOffset(0, effectiveBottomNavOffset.toInt())
                                        }
                                    }
                            .onGloballyPositioned { coords ->
                                val newHeight = if (isLandscapeNavigation) coords.size.width else coords.size.height
                                if (bottomNavHeightPx != newHeight) {
                                    bottomNavHeightPx = newHeight
                                    onBottomNavHeightChanged(newHeight)
                                }
                            },
                    ) {
                        KototoroBottomNav(
                            state = navStateFlow,
                            onItemSelected = ::navigateFromBottomNav,
                            onItemReselected = ::navigateFromBottomNav,
                            showContinueReadingButton = isLandscapeNavigation && isResumeEnabled,
                            onContinueReadingClick = onResumeClick,
                        )
                    }
                }

                if (isSearchOverlayMounted) {
                    KototoroSearchOverlay(
                        visible = isSearchOverlayVisible,
                        query = query,
                        suggestions = suggestions,
                        initialSearchKind = initialSearchKind,
                        initialSourceTypes = initialSearchSourceTypes,
                        initialContentKinds = initialSearchContentKinds,
                        languagePresets = languagePresetEntries,
                        activeLanguagePresetId = activeSourcePresetId,
                        onQueryChanged = onQueryChanged,
                        onSearch = {
                            isSearchOverlayQueryCommitted = true
                            onSearch(it)
                            isSearchOverlayVisible = false
                        },
                        onSearchWithOptions = { searchQuery, kind, sourceTypes, contentKinds, advancedQuery, pinnedOnly, hideEmpty ->
                            isSearchOverlayQueryCommitted = true
                            onSearchWithOptions(
                                searchQuery,
                                kind,
                                sourceTypes,
                                contentKinds,
                                advancedQuery,
                                pinnedOnly,
                                hideEmpty,
                            )
                            isSearchOverlayVisible = false
                        },
                        onDismissRequest = { isSearchOverlayVisible = false },
                        onLanguagePresetSelected = onLanguagePresetSelected,
                        onManageLanguagePresets = onManageLanguagePresets,
                        onExitFinished = {
                            if (!isSearchOverlayVisible) {
                                if (!isSearchOverlayQueryCommitted) {
                                    onQueryChanged(searchOverlayInitialQuery)
                                }
                                isSearchOverlayMounted = false
                                onSearchOverlayDismiss()
                            }
                        },
                        onSourceTypesChange = onSearchOverlaySourceTypesChange,
                        onContentKindsChange = onSearchOverlayContentKindsChange,
                        onContentSuggestionClick = {
                            onContentSuggestionClick(it)
                            isSearchOverlayVisible = false
                        },
                        onTrackingEntitySuggestionClick = {
                            onTrackingEntitySuggestionClick(it)
                            isSearchOverlayVisible = false
                        },
                        onTagSuggestionClick = {
                            onTagSuggestionClick(it)
                            isSearchOverlayVisible = false
                        },
                        onSourceSuggestionClick = {
                            onSourceSuggestionClick(it)
                            isSearchOverlayVisible = false
                        },
                        onAuthorSuggestionClick = {
                            onAuthorSuggestionClick(it)
                            isSearchOverlayVisible = false
                        },
                        onDeleteQuery = onDeleteQuery,
                        onVoiceInput = onVoiceInput,
                    )
                }
            }
        }
    }

    LaunchedEffect(pendingSearchNavigation?.requestId) {
        val request = pendingSearchNavigation ?: return@LaunchedEffect
        val route = SearchNavigation.createRoute(request)
        if (isSearchRoute) {
            navController.navigate(route) {
                popUpTo<SearchRoute> { inclusive = true }
                launchSingleTop = true
            }
        } else {
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
        onSearchNavigationHandled()
    }

    val exitConfirmationEnabled by appSettings.observeAsState(
        AppSettings.KEY_EXIT_CONFIRM,
    ) { isExitConfirmationEnabled }

    var lastBackTime by remember { mutableLongStateOf(0L) }
    val primaryNavItemId = mainNavItems.firstOrNull()?.id ?: org.skepsun.kototoro.R.id.nav_home

    BackHandler(enabled = !isSearchRoute && !isDetailsRoute && !isSearchOverlayMounted) {
        val shouldReturnHome = navBackStackEntry
            ?.savedStateHandle
            ?.get<Boolean>(RETURN_HOME_ON_BACK_KEY) == true
        if (shouldReturnHome) {
            navBackStackEntry?.savedStateHandle?.set(RETURN_HOME_ON_BACK_KEY, false)
            navigateToBottomNavItem(org.skepsun.kototoro.R.id.nav_home, restoreState = false)
            lastBackTime = 0L
        } else if (!currentDestination.matchesBottomNavItem(primaryNavItemId)) {
            navigateToBottomNavItem(primaryNavItemId)
            lastBackTime = 0L
        } else {
            if (!exitConfirmationEnabled) {
                (context as? Activity)?.moveTaskToBack(true)
            } else {
                val now = System.currentTimeMillis()
                if (now - lastBackTime < 2000L) {
                    (context as? Activity)?.moveTaskToBack(true)
                } else {
                    lastBackTime = now
                    Toast.makeText(
                        context,
                        org.skepsun.kototoro.R.string.confirm_exit,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}

private fun androidx.navigation.NavDestination?.isBottomNavRoute(itemId: Int): Boolean {
    return matchesBottomNavItem(itemId)
}

private fun androidx.navigation.NavDestination?.matchesBottomNavItem(itemId: Int): Boolean {
    return when (itemId) {
        org.skepsun.kototoro.R.id.nav_home -> this?.hasRoute<HomeRoute>() == true
        org.skepsun.kototoro.R.id.nav_history -> this?.hasRoute<HistoryRoute>() == true
        org.skepsun.kototoro.R.id.nav_favorites -> this?.hasRoute<FavoritesRoute>() == true
        org.skepsun.kototoro.R.id.nav_explore -> this?.hasRoute<ExploreRoute>() == true
        org.skepsun.kototoro.R.id.nav_discover -> this?.hasRoute<DiscoverRoute>() == true
        org.skepsun.kototoro.R.id.nav_feed -> this?.hasRoute<FeedRoute>() == true
        org.skepsun.kototoro.R.id.nav_local -> this?.hasRoute<LocalRoute>() == true
        org.skepsun.kototoro.R.id.nav_suggestions -> this?.hasRoute<SuggestionsRoute>() == true
        org.skepsun.kototoro.R.id.nav_bookmarks -> this?.hasRoute<BookmarksRoute>() == true
        org.skepsun.kototoro.R.id.nav_updated -> this?.hasRoute<UpdatedRoute>() == true
        else -> false
    }
}
