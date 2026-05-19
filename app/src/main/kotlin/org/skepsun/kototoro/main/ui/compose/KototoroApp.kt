package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.ui.compose.SearchNavigation
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.compose.SearchRoute
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.MaterialTheme
import org.skepsun.kototoro.core.ui.compose.LocalRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import kotlinx.coroutines.delay

@Immutable
private data class KototoroNavigationPrefs(
    val isNavBarPinned: Boolean,
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
        AppSettings.KEY_NAV_PINNED,
        AppSettings.KEY_NAV_FLOATING,
    ) {
        KototoroNavigationPrefs(
            isNavBarPinned = isNavBarPinned,
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
    val isNavBarPinned = navigationPrefs.isNavBarPinned
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
    var topBarOffset by remember { mutableFloatStateOf(0f) }
    var bottomNavOffset by remember { mutableFloatStateOf(0f) }
    var isSearchOverlayVisible by rememberSaveable { mutableStateOf(false) }
    var isSearchOverlayMounted by rememberSaveable { mutableStateOf(false) }
    var searchOverlayInitialQuery by rememberSaveable { mutableStateOf("") }
    var isSearchOverlayQueryCommitted by rememberSaveable { mutableStateOf(false) }
    var isDetailsChromeTransitionPending by rememberSaveable { mutableStateOf(false) }
    var topBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
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
        topBarHeightPx,
        bottomNavHeightPx,
        isSearchOverlayMounted,
    ) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (isSearchOverlayMounted) {
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
        }
    }

    LaunchedEffect(isLandscapeNavigation) {
        if (isLandscapeNavigation) {
            bottomNavOffset = 0f
        }
    }

    val navController = rememberNavController()
    fun navigateToBottomNavItem(itemId: Int) {
        val route = routeForBottomNavItem(itemId)
        if (!navController.currentDestination.isBottomNavRoute(itemId)) {
            navController.navigate(route) {
                popUpTo<HomeRoute> {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
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
    val shouldShowChrome = !isSearchRoute
    val isChromeOffsetFromCurrentDestination = offsetDestinationRoute == currentDestinationRoute
    val effectiveTopBarOffset = if (isChromeOffsetFromCurrentDestination) topBarOffset else 0f
    val effectiveBottomNavOffset = if (isChromeOffsetFromCurrentDestination) bottomNavOffset else 0f
    LaunchedEffect(currentDestinationRoute) {
        if (currentDestinationRoute != null && !isDetailsRoute && !isSearchRoute) {
            topBarOffset = 0f
            bottomNavOffset = 0f
            offsetDestinationRoute = currentDestinationRoute
        }
    }
    var isChromeVisible by rememberSaveable { mutableStateOf(shouldShowChrome && !isDetailsRoute) }
    var lastResolvedWasDetailsRoute by rememberSaveable { mutableStateOf(isDetailsRoute) }
    LaunchedEffect(currentDestination, shouldShowChrome, isDetailsRoute, isDetailsChromeTransitionPending) {
        if (currentDestination == null) {
            return@LaunchedEffect
        }
        when {
            !shouldShowChrome -> {
                isChromeVisible = false
                lastResolvedWasDetailsRoute = false
                isDetailsChromeTransitionPending = false
            }
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
    val scrollAlpha = if (!isChromeVisible) 0f else {
        val maxCollapse = topBarHeightPx.toFloat()
        if (maxCollapse <= 0f) 1f
        else (1f + effectiveTopBarOffset / maxCollapse).coerceIn(0f, 1f)
    }
    val chromeAlpha by animateFloatAsState(
        targetValue = scrollAlpha,
        animationSpec = tween(durationMillis = 120),
        label = "chrome_alpha",
    )
    val showBrowseSourceSettingsEntry = currentDestination?.let {
        it.hasRoute<ExploreRoute>() || it.hasRoute<DiscoverRoute>()
    } == true
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

    val maxCollapsePx = (topBarHeightPx - statusBarHeightPx).coerceAtLeast(0)
    val contentTopInsetPx = if (shouldShowChrome) {
        (topBarHeightPx + effectiveTopBarOffset).toInt().coerceIn(maxCollapsePx, topBarHeightPx)
    } else {
        0
    }
    val displayCutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val displayCutoutStartDp = displayCutoutPadding.calculateLeftPadding(LayoutDirection.Ltr)
    val displayCutoutEndDp = displayCutoutPadding.calculateRightPadding(LayoutDirection.Ltr)
    val extraPinnedBottomInsetPx = with(density) {
        if (isNavBarPinned && !isFloating) 12.dp.roundToPx() else 0
    }
    val visibleBottomNavInsetPx = if (!isNavBarPinned && isFloating) {
        0
    } else {
        (bottomNavHeightPx - effectiveBottomNavOffset).coerceAtLeast(0f).toInt() + extraPinnedBottomInsetPx
    }
    val contentBottomInsetPx = if (!shouldShowChrome || isLandscapeNavigation) {
        0
    } else {
        maxOf(visibleBottomNavInsetPx, navigationBarHeightPx)
    }
    val visibleStartInsetDp = with(density) {
        if (isLandscapeNavigation && isChromeVisible) {
            (bottomNavHeightPx - effectiveBottomNavOffset).coerceAtLeast(0f).toDp()
        } else {
            0.dp
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
                .nestedScroll(nestedScrollConnection)
                .padding(start = displayCutoutStartDp, end = displayCutoutEndDp)) {
                SharedTransitionLayout {
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides if (isSharedElementTransitionsEnabled) {
                            this@SharedTransitionLayout
                        } else {
                            null
                        },
                    ) {
                        AppNavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            contentPadding = contentPadding,
                            bottomBarOffsetPx = effectiveBottomNavOffset,
                            bottomBarHeightPx = bottomNavHeightPx,
                            pageSaveHelper = pageSaveHelper,
                            onDetailsTransitionRequested = {
                                isDetailsChromeTransitionPending = true
                            },
                            onExploreSourceSelectionTopBarChanged = { topBarOverrideState = it },
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
                                .padding(start = visibleStartInsetDp)
                                .then(if (useRuntimeHaze) Modifier.haze(hazeState) else Modifier)
                        )
                    }
                }

                if (isChromeVisible || chromeAlpha > 0f) {
                    if (topBarOverrideState != null) {
                        when (val overrideState = topBarOverrideState) {
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

                            null -> Unit
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
                            currentListMode = if (showBrowseSourceSettingsEntry) browseListMode else listMode,
                            onListModeSelected = {
                                if (showBrowseSourceSettingsEntry) {
                                    appSettings.browseListMode = it
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

                    Box(
                                modifier = Modifier
                                    .align(if (isLandscapeNavigation) Alignment.CenterStart else Alignment.BottomCenter)
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
                            onItemSelected = ::navigateToBottomNavItem,
                            onItemReselected = ::navigateToBottomNavItem,
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
}

private fun androidx.navigation.NavDestination?.isBottomNavRoute(itemId: Int): Boolean {
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
