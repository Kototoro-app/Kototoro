package org.skepsun.kototoro.main.ui.compose

import android.app.Activity
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.material3.MaterialTheme
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import androidx.compose.ui.unit.LayoutDirection

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
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
import org.skepsun.kototoro.space.ui.SpaceAction
import org.skepsun.kototoro.space.ui.SpaceSwitcherFab
import org.skepsun.kototoro.space.ui.SpaceSwitcherRailButton
import org.skepsun.kototoro.space.ui.SpaceSwitcherSheet
import org.skepsun.kototoro.space.ui.SpaceUiState
import org.skepsun.kototoro.space.ui.MediaUniverseSheet
import org.skepsun.kototoro.space.ui.MediaUniverseUiState
import org.skepsun.kototoro.search.domain.LocalEntitySuggestion
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.FoldableUtils
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.chrisbanes.haze.HazePositionStrategy
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
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarFilterRail
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.RouteScopedTopBarOverrideState
import org.skepsun.kototoro.main.ui.navigation3.DiscoverNavKey
import org.skepsun.kototoro.main.ui.navigation3.ExploreNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.FeedNavKey
import org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey
import org.skepsun.kototoro.main.ui.navigation3.HomeNavKey
import org.skepsun.kototoro.main.ui.navigation3.LocalNavKey
import org.skepsun.kototoro.main.ui.navigation3.BookmarksNavKey
import org.skepsun.kototoro.main.ui.navigation3.NavControllerMainNavigator
import org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey
import org.skepsun.kototoro.main.ui.navigation3.TopLevelNavKey
import org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey
import org.skepsun.kototoro.main.ui.navigation3.rememberSpaceNavigationStates
import org.skepsun.kototoro.main.ui.navigation3.resolveNavigationSpaceId
import org.skepsun.kototoro.main.ui.navigation3.restoreFromSpaceSession
import org.skepsun.kototoro.main.ui.navigation3.toSpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.ui.SpaceNavigationSessionUiState
import org.skepsun.kototoro.space.ui.SpaceResumeUiState
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.core.util.ext.sortedByOrdinal
import org.skepsun.kototoro.core.util.ext.animatorDurationScale
import org.skepsun.kototoro.space.ui.SpaceMotion

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

private fun routeOwnerKeyForTopLevelKey(
    key: TopLevelNavKey?,
): String? = when (key) {
    HomeNavKey -> "home"
    DiscoverNavKey -> "discover"
    HistoryNavKey -> "history"
    FavoritesNavKey -> "favorites"
    ExploreNavKey -> "explore"
    FeedNavKey -> "feed"
    LocalNavKey -> "local"
    SuggestionsNavKey -> "suggestions"
    BookmarksNavKey -> "bookmarks"
    UpdatedNavKey -> "updated"
    else -> null
}

private fun topLevelKeyForRouteOwnerKey(
    ownerKey: String?,
): TopLevelNavKey? = when (ownerKey) {
    "home" -> HomeNavKey
    "discover" -> DiscoverNavKey
    "history" -> HistoryNavKey
    "favorites" -> FavoritesNavKey
    "explore" -> ExploreNavKey
    "feed" -> FeedNavKey
    "local" -> LocalNavKey
    "suggestions" -> SuggestionsNavKey
    "bookmarks" -> BookmarksNavKey
    "updated" -> UpdatedNavKey
    else -> null
}

private fun TopLevelNavKey?.supportsDisplayModeMenu(): Boolean = when (this) {
    ExploreNavKey,
    DiscoverNavKey,
    HomeNavKey,
    HistoryNavKey,
    FavoritesNavKey,
    LocalNavKey,
    SuggestionsNavKey,
    UpdatedNavKey,
    -> true
    else -> false
}

private fun TopLevelNavKey?.supportsGridSizeSlider(): Boolean = when (this) {
    HomeNavKey,
    DiscoverNavKey,
    ExploreNavKey,
    FeedNavKey,
    HistoryNavKey,
    FavoritesNavKey,
    LocalNavKey,
    SuggestionsNavKey,
    UpdatedNavKey,
    -> true
    else -> false
}

private fun TopLevelNavKey?.titleRes(): Int? = when (this) {
    HomeNavKey -> R.string.home
    HistoryNavKey -> R.string.history
    FavoritesNavKey -> R.string.favourites
    ExploreNavKey -> R.string.explore
    DiscoverNavKey -> R.string.discover
    FeedNavKey -> R.string.feed
    LocalNavKey -> R.string.local_storage
    SuggestionsNavKey -> R.string.suggestions
    BookmarksNavKey -> R.string.bookmarks
    UpdatedNavKey -> R.string.updated
    else -> null
}

private fun lerpFloat(
    start: Float,
    endInclusive: Float,
    fraction: Float,
): Float = start + (endInclusive - start) * fraction.coerceIn(0f, 1f)

private suspend fun restoreChromeAfterDetailsDelay(
    setChromeVisible: (Boolean) -> Unit,
    clearChromeTransitionFlags: () -> Unit,
) {
    setChromeVisible(false)
    delay(MainNavigationMotion.ChromeEnterExitDelayMillis)
    setChromeVisible(true)
    clearChromeTransitionFlags()
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.renderChromeInSharedTransitionOverlay(
    sharedTransitionScope: SharedTransitionScope?,
    zIndexInOverlay: Float,
    renderInOverlay: () -> Boolean,
): Modifier {
    val scope = sharedTransitionScope ?: return this
    return with(scope) {
        this@renderChromeInSharedTransitionOverlay.renderInSharedTransitionScopeOverlay(
            zIndexInOverlay = zIndexInOverlay,
            renderInOverlay = renderInOverlay,
        )
    }
}


@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
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
    onLocalEntitySuggestionClick: (LocalEntitySuggestion) -> Unit = {},
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
    onFeedRefresh: () -> Unit = {},
    spaceUiState: SpaceUiState = SpaceUiState(),
    onSpaceAction: (SpaceAction) -> Unit = {},
    spaceNavigationSessionUiState: SpaceNavigationSessionUiState = SpaceNavigationSessionUiState(),
    onSpaceSessionChanged: (SpaceSessionSnapshot) -> Unit = {},
    spaceResumeUiState: SpaceResumeUiState = SpaceResumeUiState(),
    onSpaceResume: (SpaceId) -> Unit = {},
    mediaUniverseUiState: MediaUniverseUiState = MediaUniverseUiState(),
    onOpenMediaUniverse: () -> Unit = {},
    onDismissMediaUniverse: () -> Unit = {},
    onMediaUniverseContentClick: (Content) -> Unit = {},
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
    val isReducedVisualEffectsEnabled by appSettings.observeAsState(
        AppSettings.KEY_REDUCED_VISUAL_EFFECTS,
    ) {
        isReducedVisualEffectsEnabled
    }
    val effectiveSharedElementTransitionsEnabled =
        isSharedElementTransitionsEnabled && !isReducedVisualEffectsEnabled
    val spaceMotionMode = SpaceMotion.resolveMode(
        reducedVisualEffects = isReducedVisualEffectsEnabled,
        animatorDurationScale = context.animatorDurationScale,
    )
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
    val effectiveContentTypeFilterVisible = isContentTypeFilterVisible &&
        isContentTypeFilterVisibleSetting &&
        !spaceUiState.switcherEnabled
    val effectiveSourceTagFilterVisible = isSourceTagFilterVisible && isSourceTagFilterVisibleSetting

    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomNavHeightPx by remember { mutableIntStateOf(0) }
    var bottomNavOffset by remember { mutableFloatStateOf(0f) }
    var totalContentScrollOffset by remember { mutableFloatStateOf(0f) }
    var isLandscapeRailInteracting by remember { mutableStateOf(false) }
    var isSearchOverlayVisible by rememberSaveable { mutableStateOf(false) }
    var isSearchOverlayMounted by rememberSaveable { mutableStateOf(false) }
    var searchOverlayInitialQuery by rememberSaveable { mutableStateOf("") }
    var isSearchOverlayQueryCommitted by rememberSaveable { mutableStateOf(false) }
    var isDetailsChromeTransitionPending by rememberSaveable { mutableStateOf(false) }
    var keepTabsExpandedByScrollDirection by rememberSaveable { mutableStateOf(false) }
    val routeTopBarOverrideStates = remember { mutableStateMapOf<String, TopBarOverrideState>() }
    val routeContextualMenuActions = remember { mutableStateMapOf<String, List<KototoroTopBarMenuAction>>() }
    var globalTopBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var offsetDestinationRoute by remember { mutableStateOf<String?>(null) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarHeightPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().roundToPx()
    }
    val navigationBarHeightPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().roundToPx()
    }
    var materialTopBarScrollEnabled by remember { mutableStateOf(true) }
    val topAppBarState = rememberTopAppBarState()
    val topAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = topAppBarState,
        canScroll = {
            materialTopBarScrollEnabled &&
            !isSearchOverlayMounted &&
                !isLandscapeRailInteracting &&
                !isNavBarPinned
        },
    )
    val nestedScrollConnection = remember(
        isNavBarPinned,
        isLandscapeNavigation,
        isLandscapeRailInteracting,
        bottomNavHeightPx,
        isSearchOverlayMounted,
    ) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                totalContentScrollOffset = (totalContentScrollOffset + available.y).coerceAtMost(0f)
                if (isSearchOverlayMounted || isLandscapeRailInteracting) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                val dy = available.y
                if (!isNavBarPinned && dy != 0f) {
                    keepTabsExpandedByScrollDirection = dy > 0f
                    bottomNavOffset = if (isLandscapeNavigation) {
                        0f
                    } else {
                        (bottomNavOffset - dy).coerceIn(0f, bottomNavHeightPx.toFloat())
                    }
                } else if (isNavBarPinned) {
                    bottomNavOffset = 0f
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    LaunchedEffect(isSearchOverlayMounted) {
        if (isSearchOverlayMounted) {
            topAppBarState.heightOffset = 0f
            bottomNavOffset = 0f
            totalContentScrollOffset = 0f
            keepTabsExpandedByScrollDirection = false
        }
    }

    LaunchedEffect(isLandscapeNavigation) {
        if (isLandscapeNavigation) {
            bottomNavOffset = 0f
        }
    }

    LaunchedEffect(topBarHeightPx) {
        topAppBarState.heightOffsetLimit = -topBarHeightPx.toFloat()
    }

    val mainNavItems by appSettings.observeAsState(AppSettings.KEY_NAV_MAIN) { mainNavItems }
    val initialTopLevel = remember(mainNavItems) {
        topLevelKeyForBottomNavItem(mainNavItems.firstOrNull()?.id ?: org.skepsun.kototoro.R.id.nav_home)
    }
    val spaceNavigationStates = rememberSpaceNavigationStates(initialTopLevel = initialTopLevel)
    val navigationSpaceId = resolveNavigationSpaceId(
        activeSpaceId = spaceUiState.activeSpaceId,
        persistentNavigationEnabled = spaceUiState.persistentNavigationEnabled,
    )
    val activeNavigationState = spaceNavigationStates[navigationSpaceId]
    val mainNavState = activeNavigationState.mainNavState
    val navController = activeNavigationState.navController
    val spaceSaveableStateHolder = rememberSaveableStateHolder()
    val restoredSpaceIds = remember { mutableStateMapOf<SpaceId, Boolean>() }
    val databaseRestoredSpaceIds = remember { mutableStateMapOf<SpaceId, Boolean>() }
    val rootRestoredSpaceIds = remember { mutableStateMapOf<SpaceId, Boolean>() }
    val currentOnSpaceSessionChanged by rememberUpdatedState(onSpaceSessionChanged)
    LaunchedEffect(
        spaceNavigationSessionUiState.enabled,
        spaceNavigationSessionUiState.restorationReady,
        spaceNavigationSessionUiState.sessions,
    ) {
        if (!spaceNavigationSessionUiState.enabled) {
            restoredSpaceIds.clear()
            databaseRestoredSpaceIds.clear()
            rootRestoredSpaceIds.clear()
            return@LaunchedEffect
        }
        if (!spaceNavigationSessionUiState.restorationReady) return@LaunchedEffect
        BuiltInSpaces.contexts.forEach { context ->
            if (restoredSpaceIds[context.id] == true) return@forEach
            val state = spaceNavigationStates[context.id].mainNavState
            val session = spaceNavigationSessionUiState.sessions[context.id]
            if (session != null && state.isInitialState(initialTopLevel)) {
                state.restoreFromSpaceSession(session)
                databaseRestoredSpaceIds[context.id] = true
            }
            restoredSpaceIds[context.id] = true
        }
    }
    val isActiveSpaceRestored = restoredSpaceIds[navigationSpaceId] == true
    val isActiveDatabaseSessionApplied = databaseRestoredSpaceIds[navigationSpaceId] == true
    LaunchedEffect(
        navigationSpaceId,
        mainNavState,
        spaceNavigationSessionUiState.enabled,
        isActiveSpaceRestored,
    ) {
        if (!spaceNavigationSessionUiState.enabled || !isActiveSpaceRestored) return@LaunchedEffect
        snapshotFlow {
            mainNavState.toSpaceSessionSnapshot(
                spaceId = navigationSpaceId,
                timestamp = System.currentTimeMillis(),
            )
        }.debounce(500L).collect(currentOnSpaceSessionChanged)
    }
    DisposableEffect(
        navigationSpaceId,
        mainNavState,
        spaceNavigationSessionUiState.enabled,
        isActiveSpaceRestored,
    ) {
        onDispose {
            if (spaceNavigationSessionUiState.enabled && isActiveSpaceRestored) {
                currentOnSpaceSessionChanged(
                    mainNavState.toSpaceSessionSnapshot(
                        spaceId = navigationSpaceId,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
    val topLevelNavigator = remember(navController, mainNavState) {
        NavControllerMainNavigator(
            navController = navController,
            mainActivity = null,
            mainNavState = mainNavState,
        )
    }
    fun navigateToBottomNavItem(
        itemId: Int,
        restoreState: Boolean = true,
    ) {
        val topLevelKey = topLevelKeyForBottomNavItem(itemId)
        if (mainNavState.selectedTopLevel != topLevelKey) {
            if (restoreState) {
                topLevelNavigator.openTopLevel(topLevelKey)
            } else {
                mainNavState.navigateTopLevel(topLevelKey)
                navController.navigate(routeForTopLevelKey(topLevelKey)) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = false
                        saveState = false
                    }
                    launchSingleTop = true
                    this.restoreState = false
                }
            }
        }
    }
    fun navigateFromBottomNav(itemId: Int) = navigateToBottomNavItem(itemId)
    val startDestination = remember(initialTopLevel) {
        routeForTopLevelKey(initialTopLevel)
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    LaunchedEffect(
        navController,
        navigationSpaceId,
        isActiveSpaceRestored,
        isActiveDatabaseSessionApplied,
        spaceNavigationSessionUiState.sessions[navigationSpaceId],
    ) {
        if (!isActiveSpaceRestored || rootRestoredSpaceIds[navigationSpaceId] == true) return@LaunchedEffect
        if (!isActiveDatabaseSessionApplied) {
            rootRestoredSpaceIds[navigationSpaceId] = true
            return@LaunchedEffect
        }
        val session = spaceNavigationSessionUiState.sessions[navigationSpaceId]
        if (session == null) {
            rootRestoredSpaceIds[navigationSpaceId] = true
            return@LaunchedEffect
        }
        navController.currentBackStackEntryFlow.first()
        if (navController.previousBackStackEntry == null) {
            val selectedTopLevel = topLevelKeyForRouteOwnerKey(session.selectedTopLevel) ?: HomeNavKey
            if (topLevelKeyForDestination(navController.currentDestination) != selectedTopLevel) {
                navController.navigate(routeForTopLevelKey(selectedTopLevel)) {
                    launchSingleTop = true
                }
            }
            session.stacks[session.selectedTopLevel].orEmpty().drop(1).forEach { route ->
                when (route) {
                    is SpaceRouteSnapshot.TopLevel -> Unit
                    is SpaceRouteSnapshot.ContentList -> navController.navigate(ContentListRoute(route.sourceName))
                    is SpaceRouteSnapshot.WorkDetails -> {
                        org.skepsun.kototoro.core.nav.PendingDetailsNavigation.set(
                            org.skepsun.kototoro.details.ui.model.DetailsOrigin.EntityGraph(
                                entityId = route.entityId,
                                preferredLocalMangaId = route.requestedProjectionId,
                                initialProjectionLocalMangaId = route.requestedProjectionId,
                            ),
                        )
                        navController.navigate(DetailsRoute)
                    }
                }
            }
        }
        rootRestoredSpaceIds[navigationSpaceId] = true
    }
    val currentDestinationRoute = currentDestination?.route
    val isSearchRoute = currentDestination?.hasRoute<SearchRoute>() == true
    val isDetailsRoute = currentDestination?.hasRoute<DetailsRoute>() == true
    val isContentListRoute = currentDestination?.hasRoute<ContentListRoute>() == true
    val isImmersiveRoute = isDetailsRoute || isContentListRoute
    val shouldShowChrome = !isSearchRoute && !isImmersiveRoute
    val currentTopLevelKey = topLevelKeyForDestination(currentDestination)
        ?: if (shouldShowChrome) mainNavState.selectedTopLevel else null
    val currentTopBarOwnerKey = routeOwnerKeyForTopLevelKey(currentTopLevelKey)
    LaunchedEffect(currentDestination) {
        topLevelKeyForDestination(currentDestination)?.let(mainNavState::navigateTopLevel)
    }
    var lastChromeTopBarOwnerKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentTopBarOwnerKey) {
        if (currentTopBarOwnerKey != null) {
            lastChromeTopBarOwnerKey = currentTopBarOwnerKey
        }
    }
    val chromeTopBarOwnerKey = currentTopBarOwnerKey ?: if (isImmersiveRoute && isDetailsChromeTransitionPending) {
        lastChromeTopBarOwnerKey
    } else {
        null
    }
    val chromeTopLevelKey = currentTopLevelKey ?: topLevelKeyForRouteOwnerKey(chromeTopBarOwnerKey)
    val contextualMenuActions = chromeTopBarOwnerKey
        ?.let(routeContextualMenuActions::get)
        .orEmpty()
    val shouldReserveChromeInsets = shouldShowChrome || (isImmersiveRoute && isDetailsChromeTransitionPending)
    var isChromeVisible by rememberSaveable { mutableStateOf(shouldShowChrome && !isImmersiveRoute) }
    var pendingChromeRestoreFromDetails by rememberSaveable { mutableStateOf(isImmersiveRoute) }
    var lastHeroTransitionStartedAtMs by remember { mutableLongStateOf(0L) }
    var heroTransitionPhase by rememberSaveable { mutableStateOf(HeroTransitionPhase.Idle) }
    val shouldHideChromeForEnteringDetails =
        isDetailsChromeTransitionPending && heroTransitionPhase == HeroTransitionPhase.EnteringDetails
    val shouldDelayChromeRestoreFromDetails =
        pendingChromeRestoreFromDetails && shouldShowChrome && !isImmersiveRoute
    LaunchedEffect(currentDestination, shouldShowChrome, isImmersiveRoute, isDetailsChromeTransitionPending) {
        if (currentDestination == null) {
            return@LaunchedEffect
        }
        fun clearChromeTransitionFlags(clearPendingRestore: Boolean = true) {
            if (clearPendingRestore) {
                pendingChromeRestoreFromDetails = false
            }
            isDetailsChromeTransitionPending = false
        }
        when {
            isImmersiveRoute -> {
                pendingChromeRestoreFromDetails = true
                if (!isDetailsChromeTransitionPending) {
                    isChromeVisible = false
                    return@LaunchedEffect
                }
                isChromeVisible = true
                delay(MainNavigationMotion.ChromeEnterExitDelayMillis)
                isChromeVisible = false
                isDetailsChromeTransitionPending = false
            }
            shouldHideChromeForEnteringDetails -> {
                isChromeVisible = false
                pendingChromeRestoreFromDetails = false
            }
            !shouldShowChrome -> {
                isChromeVisible = false
                clearChromeTransitionFlags()
            }
            shouldDelayChromeRestoreFromDetails -> {
                // Wait until the details pop animation settles before restoring the main chrome.
                restoreChromeAfterDetailsDelay(
                    setChromeVisible = { isChromeVisible = it },
                    clearChromeTransitionFlags = ::clearChromeTransitionFlags,
                )
            }
            else -> {
                isChromeVisible = true
                clearChromeTransitionFlags()
            }
        }
    }
    val heroTransitionInProgress by produceState(
        initialValue = false,
        isDetailsChromeTransitionPending,
        isImmersiveRoute,
        lastHeroTransitionStartedAtMs,
    ) {
        if (!isImmersiveRoute && !isDetailsChromeTransitionPending) {
            value = false
            return@produceState
        }
        if (lastHeroTransitionStartedAtMs == 0L) {
            value = isDetailsChromeTransitionPending
            return@produceState
        }
        value = isDetailsChromeTransitionPending || isImmersiveRoute
        val elapsed = heroTransitionTimestampMs() - lastHeroTransitionStartedAtMs
        if (elapsed < MainNavigationMotion.HeroProtectionMillis) {
            value = true
            delay(MainNavigationMotion.HeroProtectionMillis - elapsed)
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
    val showBrowseSourceSettingsEntry = chromeTopLevelKey == ExploreNavKey || chromeTopLevelKey == DiscoverNavKey
    val resolvedTopBarOverrideState = chromeTopBarOwnerKey
        ?.let(routeTopBarOverrideStates::get)
        ?: globalTopBarOverrideState
    val layeredTopBarOverrideState = resolvedTopBarOverrideState as? LayeredTopBarOverrideState
    val topTabsOverrideState = layeredTopBarOverrideState?.tabsState ?: (resolvedTopBarOverrideState as? CompactTabsTopBarOverrideState)
    val topFilterRailOverrideState = layeredTopBarOverrideState?.filterRailState
    val effectiveTopBarOverrideState = if (layeredTopBarOverrideState != null) {
        layeredTopBarOverrideState.contextualOverrideState
    } else {
        resolvedTopBarOverrideState
    }
    val hasSelectionTopChrome =
        effectiveTopBarOverrideState is ExploreSourceSelectionTopBarState ||
            effectiveTopBarOverrideState is ContentSelectionTopBarOverrideState
    val shouldUseMaterialTopBarScroll = shouldShowChrome && !hasSelectionTopChrome
    val isChromeOffsetFromCurrentDestination = offsetDestinationRoute == currentDestinationRoute
    val effectiveTopBarOffset = if (isChromeOffsetFromCurrentDestination && shouldUseMaterialTopBarScroll) {
        topAppBarState.heightOffset
    } else {
        0f
    }
    val effectiveBottomNavOffset = if (isChromeOffsetFromCurrentDestination) bottomNavOffset else 0f
    LaunchedEffect(shouldUseMaterialTopBarScroll) {
        materialTopBarScrollEnabled = shouldUseMaterialTopBarScroll
        if (!shouldUseMaterialTopBarScroll) {
            topAppBarState.heightOffset = 0f
        }
    }
    LaunchedEffect(currentDestinationRoute, currentTopBarOwnerKey) {
        if (currentDestinationRoute != null && !isImmersiveRoute && !isSearchRoute) {
            topAppBarState.heightOffset = 0f
            bottomNavOffset = 0f
            totalContentScrollOffset = 0f
            keepTabsExpandedByScrollDirection = false
            offsetDestinationRoute = currentDestinationRoute
        }
    }
    val scrollAlpha = if (!isChromeVisible) 0f else {
        val maxCollapse = topBarHeightPx.toFloat()
        if (maxCollapse <= 0f) 1f
        else (1f + effectiveTopBarOffset / maxCollapse).coerceIn(0f, 1f)
    }
    val shouldKeepTabsExpandedWhenCollapsed = layeredTopBarOverrideState?.keepTabsExpandedWhenCollapsed == true
    val shouldKeepTabsVisible = !isNavBarPinned &&
        shouldKeepTabsExpandedWhenCollapsed &&
        !isDetailsChromeTransitionPending &&
        topTabsOverrideState != null &&
        keepTabsExpandedByScrollDirection &&
        scrollAlpha < 0.98f
    val effectiveChromeAlphaTarget = if (shouldKeepTabsVisible) {
        1f
    } else {
        scrollAlpha
    }
    val effectiveCompactTabsTopBarOffset = if (shouldKeepTabsVisible) {
        0f
    } else {
        effectiveTopBarOffset
    }
    val animatedChromeAlpha by animateFloatAsState(
        targetValue = effectiveChromeAlphaTarget,
        animationSpec = tween(durationMillis = MainNavigationMotion.ChromeAlphaMillis),
        label = "chrome_alpha",
    )
    val chromeAlpha = animatedChromeAlpha
    val isHomeRoute = chromeTopLevelKey == HomeNavKey
    val supportsDisplayModeMenu = chromeTopLevelKey.supportsDisplayModeMenu()
    val supportsGridSizeSlider = chromeTopLevelKey.supportsGridSizeSlider()
    val isFavoritesRoute = chromeTopLevelKey == FavoritesNavKey
    val canShowSpaceSwitcherEntry = chromeTopLevelKey != HistoryNavKey
    val fallbackFavoritesSortOrders = if (isFavoritesRoute) ListSortOrder.FAVORITES.sortedByOrdinal() else emptyList()
    val globalFavoritesSortOrder by appSettings.observeAsState(keys = arrayOf(AppSettings.KEY_FAVORITES_ORDER)) {
        allFavoritesSortOrder
    }
    val showAllUpdates by appSettings.observeAsState(keys = arrayOf(org.skepsun.kototoro.core.prefs.AppSettings.KEY_SHOW_ALL_UPDATES)) {
        showAllUpdates
    }
    val feedLimit by appSettings.observeAsState(keys = arrayOf(org.skepsun.kototoro.core.prefs.AppSettings.KEY_FEED_LIMIT)) {
        feedLimit
    }
    val sortOrders = layeredTopBarOverrideState?.sortOrders?.takeIf { it.isNotEmpty() } ?: fallbackFavoritesSortOrders
    val selectedSortOrder = layeredTopBarOverrideState?.selectedSortOrder ?: if (isFavoritesRoute) {
        globalFavoritesSortOrder
    } else {
        null
    }
    val onDisplaySortOrderSelected = layeredTopBarOverrideState?.onSortOrderSelected ?: { order: ListSortOrder ->
        if (isFavoritesRoute) {
            appSettings.allFavoritesSortOrder = order
        }
    }
    val displayOptionsExtraContent: (@Composable (() -> Unit) -> Unit)? = if (chromeTopLevelKey == FeedNavKey) {
        { dismiss ->
            FeedDisplayOptionsContent(
                showAllUpdates = showAllUpdates,
                onShowAllUpdatesChanged = { appSettings.showAllUpdates = it },
                feedLimit = feedLimit,
                onFeedLimitChanged = { appSettings.feedLimit = it },
                onFeedRefresh = {
                    onFeedRefresh()
                    dismiss()
                },
            )
        }
    } else {
        null
    }

    LaunchedEffect(currentTopLevelKey) {
        val mappedId = currentTopLevelKey?.let(::bottomNavItemIdForTopLevelKey) ?: -1
        if (mappedId != -1) {
            onNavDestinationChanged(mappedId)
        }
    }

    val reservedTopBarHeightPx = maxOf(
        topBarHeightPx,
        statusBarHeightPx + with(density) { 44.dp.roundToPx() },
    )
    val maxCollapsePx = (reservedTopBarHeightPx - statusBarHeightPx).coerceAtLeast(0)
    val contentTopInsetPx = if (shouldReserveChromeInsets) {
        (reservedTopBarHeightPx + effectiveTopBarOffset).toInt()
            .coerceIn(maxCollapsePx, reservedTopBarHeightPx)
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
    val contentBottomInsetPx = if (!shouldReserveChromeInsets) {
        0
    } else if (isLandscapeNavigation) {
        navigationBarHeightPx
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
    var chromeSharedTransitionScope by remember { mutableStateOf<SharedTransitionScope?>(null) }


    KototoroTheme(cornerRadius = cornerRadius) {
        val hazeState = remember { HazeState().apply { positionStrategy = HazePositionStrategy.Screen } }
        val glassPrefs = rememberGlassPrefs(appSettings)
        val railAnimationFactor = rememberRailAnimationFactor(appSettings)
        val useRuntimeHaze = glassPrefs.isGlassEffectEnabled && supportsRuntimeHaze()
        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalGlassPrefs provides glassPrefs,
            LocalRailAnimationFactor provides railAnimationFactor,
        ) {
            val immersiveStrength = ((LocalGlassPrefs.current?.immersiveStrengthPercent ?: 65).coerceIn(0, 100)) / 100f
            val isDarkTheme = isSystemInDarkTheme()
            val immersiveBaseColor = if (isDarkTheme) Color.Black else Color.White
            val immersiveTransparent = Color.Transparent
            val topGradientAlpha = if (topBarHeightPx > 0) {
                (-totalContentScrollOffset / topBarHeightPx.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val topImmersiveOverflowPx = with(density) { 6.dp.roundToPx() }
            val topImmersiveHeight = with(density) {
                (statusBarHeightPx + (topBarHeightPx - statusBarHeightPx) + topImmersiveOverflowPx)
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
            val mainShellChrome: @Composable BoxScope.() -> Unit = {
                if (shouldShowChrome || isChromeVisible || chromeAlpha > 0f) {
                    if (!isImmersiveRoute) {
                        ImmersiveEdgeGradient(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .graphicsLayer { alpha = topGradientAlpha },
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

                    MainTopChrome(
                        effectiveTopBarOverrideState = effectiveTopBarOverrideState,
                        isLandscapeNavigation = isLandscapeNavigation,
                        chromeSharedTransitionScope = chromeSharedTransitionScope,
                        heroTransitionInProgress = heroTransitionInProgress,
                        isDetailsChromeTransitionPending = isDetailsChromeTransitionPending,
                        visibleStartInsetDp = visibleStartInsetDp,
                        effectiveTopBarOffset = effectiveTopBarOffset,
                        chromeAlpha = chromeAlpha,
                        onTopBarHeightMeasured = { newHeight ->
                            if (topBarHeightPx != newHeight) {
                                topBarHeightPx = newHeight
                                onTopBarHeightChanged(newHeight)
                            }
                        },
                        query = query,
                        titleRes = chromeTopLevelKey.titleRes(),
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
                        topTabsOverrideState = topTabsOverrideState,
                        topFilterRailOverrideState = topFilterRailOverrideState,
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
                        forceCompactTabsExpanded = shouldKeepTabsVisible,
                        effectiveCompactTabsTopBarOffset = effectiveCompactTabsTopBarOffset,
                        sortOrders = sortOrders,
                        selectedSortOrder = selectedSortOrder,
                        onSortOrderSelected = onDisplaySortOrderSelected,
                        displayOptionsExtraContent = displayOptionsExtraContent,
                    )
                    MainBottomChrome(
                        isLandscapeNavigation = isLandscapeNavigation,
                        chromeSharedTransitionScope = chromeSharedTransitionScope,
                        heroTransitionInProgress = heroTransitionInProgress,
                        isDetailsChromeTransitionPending = isDetailsChromeTransitionPending,
                        effectiveBottomNavOffset = effectiveBottomNavOffset,
                        onLandscapeRailInteractingChange = { isLandscapeRailInteracting = it },
                        onBottomNavHeightMeasured = { newHeight ->
                            if (bottomNavHeightPx != newHeight) {
                                bottomNavHeightPx = newHeight
                                onBottomNavHeightChanged(newHeight)
                            }
                        },
                        navStateFlow = navStateFlow,
                        onItemSelected = ::navigateFromBottomNav,
                        onItemReselected = ::navigateFromBottomNav,
                        railHeaderContent = if (spaceUiState.switcherEnabled && canShowSpaceSwitcherEntry) {
                            {
                                SpaceSwitcherRailButton(
                                    activeSpaceId = spaceUiState.activeSpaceId,
                                    onClick = { onSpaceAction(SpaceAction.OpenSwitcher) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                    if (!isLandscapeNavigation && spaceUiState.switcherEnabled && canShowSpaceSwitcherEntry) {
                        SpaceSwitcherFab(
                            activeSpaceId = spaceUiState.activeSpaceId,
                            expanded = totalContentScrollOffset >= 0f,
                            onClick = { onSpaceAction(SpaceAction.OpenSwitcher) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = 20.dp,
                                    bottom = with(density) { bottomNavHeightPx.toDp() } + 20.dp,
                                ),
                        )
                    }
                    SpaceSwitcherSheet(
                        state = spaceUiState,
                        onAction = onSpaceAction,
                        resumeItems = spaceResumeUiState.items,
                        onResume = onSpaceResume,
                        onOpenMediaUniverse = {
                            onSpaceAction(SpaceAction.DismissSwitcher)
                            onOpenMediaUniverse()
                        },
                    )
                    MediaUniverseSheet(
                        state = mediaUniverseUiState,
                        onDismiss = onDismissMediaUniverse,
                        onContentClick = onMediaUniverseContentClick,
                    )
                }
            }
            Box(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .nestedScroll(nestedScrollConnection)
                .padding(start = displayCutoutStartDp, end = displayCutoutEndDp)) {
                SharedTransitionLayout {
                    SideEffect {
                        chromeSharedTransitionScope = if (effectiveSharedElementTransitionsEnabled) {
                            this@SharedTransitionLayout
                        } else {
                            null
                        }
                    }
                    CompositionLocalProvider(
                        LocalHeroTransitionInProgress provides false,
                        LocalHeroReturnTransitionInProgress provides false,
                        LocalHeroTransitionPhase provides HeroTransitionPhase.Idle,
                        LocalSharedTransitionScope provides if (effectiveSharedElementTransitionsEnabled) {
                            this@SharedTransitionLayout
                        } else {
                            null
                        },
                    ) {
                        val renderSpaceNavigation: @Composable (SpaceId) -> Unit = { renderedSpaceId ->
                            val renderedNavigationState = spaceNavigationStates[renderedSpaceId]
                            val renderedNavController = renderedNavigationState.navController
                            spaceSaveableStateHolder.SaveableStateProvider(renderedSpaceId.value) {
                                key(renderedSpaceId.value) {
                                    AppNavGraph(
                                        navController = renderedNavController,
                                        mainNavState = renderedNavigationState.mainNavState,
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
                                            if (effectiveSharedElementTransitionsEnabled) {
                                                isDetailsChromeTransitionPending = true
                                                heroTransitionPhase = HeroTransitionPhase.ReturningFromDetails
                                                lastHeroTransitionStartedAtMs = heroTransitionTimestampMs()
                                            }
                                        },
                                        activeSpaceId = renderedSpaceId.takeIf { spaceUiState.switcherEnabled },
                                        onSpaceSwitcherClick = {
                                            onSpaceAction(SpaceAction.OpenSwitcher)
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
                                        onContextualMenuActionsChanged = { state ->
                                            if (state.actions.isEmpty()) {
                                                routeContextualMenuActions.remove(state.ownerRoute)
                                            } else {
                                                routeContextualMenuActions[state.ownerRoute] = state.actions
                                            }
                                        },
                                        onOpenSearch = { request ->
                                            val route = SearchNavigation.createRoute(request)
                                            if (renderedNavController.currentDestination?.hasRoute<SearchRoute>() == true) {
                                                renderedNavController.navigate(route) {
                                                    popUpTo<SearchRoute> { inclusive = true }
                                                    launchSingleTop = true
                                                }
                                            } else {
                                                renderedNavController.navigate(route) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        if (spaceUiState.persistentNavigationEnabled) {
                            AnimatedContent(
                                targetState = navigationSpaceId,
                                transitionSpec = { SpaceMotion.contentTransform(spaceMotionMode) },
                                contentKey = SpaceId::value,
                                label = "space_content",
                            ) { renderedSpaceId ->
                                renderSpaceNavigation(renderedSpaceId)
                            }
                        } else {
                            renderSpaceNavigation(navigationSpaceId)
                        }
                    }
                }
                mainShellChrome()

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
                        },
                        onLocalEntitySuggestionClick = {
                            onLocalEntitySuggestionClick(it)
                        },
                        onTrackingEntitySuggestionClick = {
                            onTrackingEntitySuggestionClick(it)
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

    BackHandler(enabled = !isSearchRoute && !isImmersiveRoute && !isSearchOverlayMounted) {
        if (currentTopLevelKey != topLevelKeyForBottomNavItem(primaryNavItemId)) {
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BoxScope.MainTopChrome(
    effectiveTopBarOverrideState: TopBarOverrideState?,
    isLandscapeNavigation: Boolean,
    chromeSharedTransitionScope: SharedTransitionScope?,
    heroTransitionInProgress: Boolean,
    isDetailsChromeTransitionPending: Boolean,
    visibleStartInsetDp: androidx.compose.ui.unit.Dp,
    effectiveTopBarOffset: Float,
    chromeAlpha: Float,
    onTopBarHeightMeasured: (Int) -> Unit,
    query: String,
    titleRes: Int?,
    onSearchClick: () -> Unit,
    onOpenListOptions: () -> Unit,
    onSettingsClick: () -> Unit,
    onSourceSettingsClick: () -> Unit,
    onManageSourcesClick: () -> Unit,
    onTrackingAccountsClick: () -> Unit,
    isAppUpdateAvailable: Boolean,
    onAppUpdateClick: () -> Unit,
    isIncognitoModeEnabled: Boolean,
    onIncognitoToggle: () -> Unit,
    isLanguagePresetFilterVisible: Boolean,
    languagePresetEntries: List<SourcePreset>,
    activeLanguagePresetId: Long,
    onLanguagePresetSelected: (Long) -> Unit,
    onManageLanguagePresets: () -> Unit,
    topTabsOverrideState: CompactTabsTopBarOverrideState?,
    topFilterRailOverrideState: CompactFilterRailOverrideState?,
    selectedContentType: ContentType?,
    enabledContentTypes: Set<ContentType>,
    isContentTypeFilterVisible: Boolean,
    onContentTypeSelected: (ContentType?) -> Unit,
    selectedSourceTags: Set<SourceTag>,
    sourceTagEntries: List<SourceTag>,
    enabledSourceTags: Set<SourceTag>,
    isSourceTagFilterVisible: Boolean,
    onSourceTagFilterClick: (android.view.View?) -> Boolean,
    onSourceTagSelected: (SourceTag?) -> Unit,
    supportsDisplayModeMenu: Boolean,
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
    supportsGridSizeSlider: Boolean,
    gridSize: Int,
    onGridSizeChange: (Int) -> Unit,
    isBrowseTrackingRecommendationsEnabled: Boolean?,
    onBrowseTrackingRecommendationsChange: ((Boolean) -> Unit)?,
    isBrowseMoreTrackingRecommendationsEnabled: Boolean?,
    onBrowseMoreTrackingRecommendationsChange: ((Boolean) -> Unit)?,
    showSourceSettingsEntry: Boolean,
    contextualMenuActions: List<KototoroTopBarMenuAction>,
    forceCompactTabsExpanded: Boolean,
    effectiveCompactTabsTopBarOffset: Float,
    sortOrders: List<org.skepsun.kototoro.list.domain.ListSortOrder> = emptyList(),
    selectedSortOrder: org.skepsun.kototoro.list.domain.ListSortOrder? = null,
    onSortOrderSelected: (org.skepsun.kototoro.list.domain.ListSortOrder) -> Unit = {},
    displayOptionsExtraContent: (@Composable (() -> Unit) -> Unit)? = null,
) {
    val topChromeModifier = Modifier
        .align(if (isLandscapeNavigation) Alignment.TopStart else Alignment.TopCenter)
        .then(if (isLandscapeNavigation) Modifier.fillMaxWidth() else Modifier)
        .renderChromeInSharedTransitionOverlay(
            sharedTransitionScope = chromeSharedTransitionScope,
            zIndexInOverlay = 2f,
            renderInOverlay = {
                heroTransitionInProgress || isDetailsChromeTransitionPending
            },
        )
        .padding(start = visibleStartInsetDp)
        .offset { androidx.compose.ui.unit.IntOffset(0, effectiveTopBarOffset.toInt()) }
        .graphicsLayer { alpha = chromeAlpha }
        .onGloballyPositioned { coords -> onTopBarHeightMeasured(coords.size.height) }

    if (effectiveTopBarOverrideState != null && effectiveTopBarOverrideState !is CompactTabsTopBarOverrideState) {
        MainSelectionTopChrome(
            effectiveTopBarOverrideState = effectiveTopBarOverrideState,
            modifier = topChromeModifier,
        )
    } else {
        KototoroTopBar(
            query = query,
            titleRes = titleRes,
            onSearchClick = onSearchClick,
            onOpenListOptions = onOpenListOptions,
            onSettingsClick = onSettingsClick,
            onSourceSettingsClick = onSourceSettingsClick,
            onManageSourcesClick = onManageSourcesClick,
            onTrackingAccountsClick = onTrackingAccountsClick,
            isAppUpdateAvailable = isAppUpdateAvailable,
            onAppUpdateClick = onAppUpdateClick,
            isIncognitoModeEnabled = isIncognitoModeEnabled,
            onIncognitoToggle = onIncognitoToggle,
            isLanguagePresetFilterVisible = isLanguagePresetFilterVisible,
            languagePresetEntries = languagePresetEntries,
            activeLanguagePresetId = activeLanguagePresetId,
            onLanguagePresetSelected = onLanguagePresetSelected,
            onManageLanguagePresets = onManageLanguagePresets,
            compactTabsState = topTabsOverrideState,
            filterRailState = topFilterRailOverrideState,
            selectedContentType = selectedContentType,
            enabledContentTypes = enabledContentTypes,
            isContentTypeFilterVisible = isContentTypeFilterVisible,
            onContentTypeSelected = onContentTypeSelected,
            selectedSourceTags = selectedSourceTags,
            sourceTagEntries = sourceTagEntries,
            enabledSourceTags = enabledSourceTags,
            isSourceTagFilterVisible = isSourceTagFilterVisible,
            onSourceTagFilterClick = onSourceTagFilterClick,
            onSourceTagSelected = onSourceTagSelected,
            supportsDisplayModeMenu = supportsDisplayModeMenu,
            currentListMode = currentListMode,
            onListModeSelected = onListModeSelected,
            supportsGridSizeSlider = supportsGridSizeSlider,
            gridSize = gridSize,
            onGridSizeChange = onGridSizeChange,
            isBrowseTrackingRecommendationsEnabled = isBrowseTrackingRecommendationsEnabled,
            onBrowseTrackingRecommendationsChange = onBrowseTrackingRecommendationsChange,
            isBrowseMoreTrackingRecommendationsEnabled = isBrowseMoreTrackingRecommendationsEnabled,
            onBrowseMoreTrackingRecommendationsChange = onBrowseMoreTrackingRecommendationsChange,
            showSourceSettingsEntry = showSourceSettingsEntry,
            contextualMenuActions = contextualMenuActions,
            forceCompactTabsExpanded = forceCompactTabsExpanded,
            sortOrders = sortOrders,
            selectedSortOrder = selectedSortOrder,
            onSortOrderSelected = onSortOrderSelected,
            displayOptionsExtraContent = displayOptionsExtraContent,
            modifier = topChromeModifier.offset {
                androidx.compose.ui.unit.IntOffset(0, (effectiveCompactTabsTopBarOffset - effectiveTopBarOffset).toInt())
            },
        )
    }
}

@Composable
private fun FeedDisplayOptionsContent(
    showAllUpdates: Boolean,
    onShowAllUpdatesChanged: (Boolean) -> Unit,
    feedLimit: Int,
    onFeedLimitChanged: (Int) -> Unit,
    onFeedRefresh: () -> Unit,
) {
    val jumps = remember { listOf(50, 100, 200, 500, 1000, 2000) }
    val limitIndex = remember(feedLimit) { jumps.indexOf(feedLimit).coerceAtLeast(0) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_all_updates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = showAllUpdates,
                onCheckedChange = onShowAllUpdatesChanged,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.feed_visible_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = feedLimit.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            KototoroSlider(
                value = limitIndex.toFloat(),
                onValueChange = { index ->
                    onFeedLimitChanged(jumps[index.roundToInt()])
                },
                valueRange = 0f..(jumps.size - 1).toFloat(),
                steps = jumps.size - 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(visible = showAllUpdates) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.feed_behavior_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onFeedRefresh,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(R.string.trigger_update_now))
                }
            }
        }
    }
}

@Composable
private fun MainSelectionTopChrome(
    effectiveTopBarOverrideState: TopBarOverrideState,
    modifier: Modifier = Modifier,
) {
    when (effectiveTopBarOverrideState) {
        is ExploreSourceSelectionTopBarState -> {
            ExploreSelectionTopBar(
                selectedCount = effectiveTopBarOverrideState.selectedCount,
                isSingleSelection = effectiveTopBarOverrideState.isSingleSelection,
                canPin = effectiveTopBarOverrideState.canPin,
                canUnpin = effectiveTopBarOverrideState.canUnpin,
                canDisable = effectiveTopBarOverrideState.canDisable,
                canDelete = effectiveTopBarOverrideState.canDelete,
                markEmptyTitleRes = effectiveTopBarOverrideState.markEmptyTitleRes,
                onClearSelection = effectiveTopBarOverrideState.onClearSelection,
                onSettings = effectiveTopBarOverrideState.onSettings,
                onDisable = effectiveTopBarOverrideState.onDisable,
                onDelete = effectiveTopBarOverrideState.onDelete,
                onShortcut = effectiveTopBarOverrideState.onShortcut,
                onPin = effectiveTopBarOverrideState.onPin,
                onUnpin = effectiveTopBarOverrideState.onUnpin,
                onToggleEmptyAvailability = effectiveTopBarOverrideState.onToggleEmptyAvailability,
                modifier = modifier,
            )
        }

        is ContentSelectionTopBarOverrideState -> {
            org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar(
                selectedCount = effectiveTopBarOverrideState.selectedCount,
                isAllNonLocal = effectiveTopBarOverrideState.isAllNonLocal,
                isSingleSelection = effectiveTopBarOverrideState.isSingleSelection,
                showRemoveOption = effectiveTopBarOverrideState.showRemoveOption,
                supportedActions = effectiveTopBarOverrideState.supportedActions,
                allPinned = effectiveTopBarOverrideState.allPinned,
                preferredInlineActions = effectiveTopBarOverrideState.preferredInlineActions,
                removeActionIconRes = effectiveTopBarOverrideState.removeActionIconRes,
                removeActionTitleRes = effectiveTopBarOverrideState.removeActionTitleRes,
                fixActionTitleRes = effectiveTopBarOverrideState.fixActionTitleRes,
                onClearSelection = effectiveTopBarOverrideState.onClearSelection,
                onActionClick = effectiveTopBarOverrideState.onActionClick,
                modifier = modifier,
            )
        }

        is CompactTabsTopBarOverrideState -> Unit
        is LayeredTopBarOverrideState -> Unit
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BoxScope.MainBottomChrome(
    isLandscapeNavigation: Boolean,
    chromeSharedTransitionScope: SharedTransitionScope?,
    heroTransitionInProgress: Boolean,
    isDetailsChromeTransitionPending: Boolean,
    effectiveBottomNavOffset: Float,
    onLandscapeRailInteractingChange: (Boolean) -> Unit,
    onBottomNavHeightMeasured: (Int) -> Unit,
    navStateFlow: StateFlow<BottomNavState>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    railHeaderContent: (@Composable () -> Unit)?,
) {
    Box(
        modifier = Modifier
            .align(if (isLandscapeNavigation) Alignment.CenterStart else Alignment.BottomCenter)
            .renderChromeInSharedTransitionOverlay(
                sharedTransitionScope = chromeSharedTransitionScope,
                zIndexInOverlay = 1f,
                renderInOverlay = {
                    heroTransitionInProgress || isDetailsChromeTransitionPending
                },
            )
            .then(
                if (isLandscapeNavigation) {
                    Modifier.pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> onLandscapeRailInteractingChange(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> onLandscapeRailInteractingChange(false)
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
                onBottomNavHeightMeasured(newHeight)
            },
    ) {
        KototoroBottomNav(
            state = navStateFlow,
            onItemSelected = onItemSelected,
            onItemReselected = onItemReselected,
            railHeaderContent = railHeaderContent,
        )
    }
}
