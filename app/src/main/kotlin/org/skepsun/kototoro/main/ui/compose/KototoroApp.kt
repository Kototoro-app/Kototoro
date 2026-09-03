package org.skepsun.kototoro.main.ui.compose


import android.app.Activity
import android.util.Log
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.ImmersiveBottomGradientStops
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeFeatherExtension
import org.skepsun.kototoro.core.ui.compose.ImmersiveTopGradientStops
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.resolveTopImmersiveAlpha
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.widgets.BottomNavState
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefs
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalScrollToTopEvents
import org.skepsun.kototoro.core.ui.compose.LiquidGlassBackdropHost
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdropHost
import org.skepsun.kototoro.core.ui.compose.DynamicArtworkBackdrop
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.ui.SpaceAction
import org.skepsun.kototoro.space.ui.SpaceSidekick
import org.skepsun.kototoro.space.ui.SpaceUiState
import org.skepsun.kototoro.search.domain.LocalEntitySuggestion
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.ui.suggestion.model.TrackingEntity
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableLongStateOf
import org.skepsun.kototoro.core.ui.compose.LocalRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.HeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.LocalHeroReturnTransitionInProgress
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMotionStyle
import org.skepsun.kototoro.core.ui.theme.LocalSurfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalChromeScrollOverlap
import org.skepsun.kototoro.core.ui.theme.MotionStyle
import org.skepsun.kototoro.core.ui.theme.SurfaceStyle
import androidx.compose.material3.Surface
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.heroTransitionTimestampMs
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.RouteScopedTopBarOverrideState
import org.skepsun.kototoro.main.ui.navigation3.ContentListNavKey
import org.skepsun.kototoro.main.ui.navigation3.DetailsNavKey
import org.skepsun.kototoro.main.ui.navigation3.DiscoverNavKey
import org.skepsun.kototoro.main.ui.navigation3.ExploreNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.FeedNavKey
import org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey
import org.skepsun.kototoro.main.ui.navigation3.HomeNavKey
import org.skepsun.kototoro.main.ui.navigation3.LocalNavKey
import org.skepsun.kototoro.main.ui.navigation3.SearchNavKey
import org.skepsun.kototoro.main.ui.navigation3.BookmarksNavKey
import org.skepsun.kototoro.main.ui.navigation3.MainNavigator
import org.skepsun.kototoro.main.ui.navigation3.MainStateNavigator
import org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey
import org.skepsun.kototoro.main.ui.navigation3.TopLevelNavKey
import org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey
import org.skepsun.kototoro.main.ui.navigation3.rememberSpaceNavigationStates
import org.skepsun.kototoro.main.ui.navigation3.resolveNavigationSpaceId
import org.skepsun.kototoro.main.ui.navigation3.restoreFromSpaceSession
import org.skepsun.kototoro.main.ui.navigation3.toSpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceKind
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.ui.SpaceNavigationSessionUiState
import org.skepsun.kototoro.space.ui.SpaceResumeUiState
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.core.util.ext.sortedByOrdinal
import org.skepsun.kototoro.core.util.ext.animatorDurationScale
import org.skepsun.kototoro.space.ui.SpaceMotion
import org.skepsun.kototoro.space.ui.SpaceMotionMode
import org.skepsun.kototoro.space.ui.SpaceTransitionCurtain
import org.skepsun.kototoro.space.ui.SpaceTransitionPhase
import org.skepsun.kototoro.space.ui.SpaceTransitionState
import org.skepsun.kototoro.space.ui.isSpaceCurtainRevealHost
import org.skepsun.kototoro.space.ui.ImmersiveSpaceSwitcherTransition
import org.skepsun.kototoro.space.ui.LocalBrowseSpaceId

private const val SpaceFabTraceTag = "SpaceFabTrace"
private const val SpaceChromeTraceTag = "SpaceChromeTrace"
private val MainResumeCoverRequestSize = Size(width = 128, height = 128)

@OptIn(ExperimentalMaterial3Api::class)
private class SpaceChromeScrollState {
    val topAppBarState = TopAppBarState(
        initialHeightOffsetLimit = -Float.MAX_VALUE,
        initialHeightOffset = 0f,
        initialContentOffset = 0f,
    )
    val topBarHeightPx = mutableIntStateOf(0)
    val bottomNavOffset = mutableFloatStateOf(0f)
    val totalContentScrollOffset = mutableFloatStateOf(0f)
    val keepTabsExpandedByScrollDirection = mutableStateOf(false)
    val offsetDestinationRoute = mutableStateOf<String?>(null)
    val offsetDestinationOwnerKey = mutableStateOf<String?>(null)
}

private inline fun traceSpaceFab(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d(SpaceFabTraceTag, message())
    }
}

private inline fun traceSpaceChrome(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d(SpaceChromeTraceTag, message())
    }
}

@Composable
private fun rememberMainResumeCoverRequest(content: Content?): ImageRequest? {
    val context = LocalContext.current
    val coverUrl = content?.coverUrl?.takeIfUsableImageUri()
        ?: content?.largeCoverUrl?.takeIfUsableImageUri()
    return remember(context, content?.id, content?.source?.name, content?.url, coverUrl) {
        if (content == null || coverUrl == null) {
            null
        } else {
            val cacheKey = contentCoverCacheKey(content, coverUrl)
            ImageRequest.Builder(context)
                .data(coverUrl)
                .size(MainResumeCoverRequestSize)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .crossfade(true)
                .mangaExtra(content)
                .build()
        }
    }
}

@Immutable
internal data class KototoroNavigationPrefs(
    val isFloating: Boolean,
    val isLayeredSurface: Boolean,
    val adjacentFabSize: Dp,
)

@Immutable
internal data class KototoroDisplayPrefs(
    val activeSourcePresetId: Long,
    val listMode: ListMode,
    val browseListMode: ListMode,
    val gridSize: Int,
    val cornerRadius: Int,
    val isBrowseTrackingRecommendationsEnabled: Boolean,
    val isBrowseMoreTrackingRecommendationsEnabled: Boolean,
)

@Immutable
internal data class KototoroFilterVisibilityPrefs(
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
    FavoritesNavKey -> null
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
internal fun Modifier.renderChromeInSharedTransitionOverlay(
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


@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KototoroApp(
    mainAppState: MainAppState,
) {
    val appSettings = mainAppState.appSettings
    val navStateFlow = mainAppState.navStateFlow
    val pageSaveHelper = mainAppState.pageSaveHelper
    val lastReadContent = mainAppState.lastReadContent
    val query = mainAppState.query
    val suggestions = mainAppState.suggestions
    val onQueryChanged = mainAppState.onQueryChanged
    val onSearch = mainAppState.onSearch
    val initialSearchKind = mainAppState.initialSearchKind
    val initialSearchSourceTypes = mainAppState.initialSearchSourceTypes
    val initialSearchContentKinds = mainAppState.initialSearchContentKinds
    val onSearchWithOptions = mainAppState.onSearchWithOptions
    val onSearchOverlaySourceTypesChange = mainAppState.onSearchOverlaySourceTypesChange
    val onSearchOverlayContentKindsChange = mainAppState.onSearchOverlayContentKindsChange
    val onSearchOverlayDismiss = mainAppState.onSearchOverlayDismiss
    val onContentSuggestionClick = mainAppState.onContentSuggestionClick
    val onLocalEntitySuggestionClick = mainAppState.onLocalEntitySuggestionClick
    val onTrackingEntitySuggestionClick = mainAppState.onTrackingEntitySuggestionClick
    val onTagSuggestionClick = mainAppState.onTagSuggestionClick
    val onSourceSuggestionClick = mainAppState.onSourceSuggestionClick
    val onAuthorSuggestionClick = mainAppState.onAuthorSuggestionClick
    val onDeleteQuery = mainAppState.onDeleteQuery
    val onVoiceInput = mainAppState.onVoiceInput
    val onOpenListOptions = mainAppState.onOpenListOptions
    val onHomeDisplayOptionsClick = mainAppState.onHomeDisplayOptionsClick
    val onSettingsClick = mainAppState.onSettingsClick
    val onHelpClick = mainAppState.onHelpClick
    val onSourceSettingsClick = mainAppState.onSourceSettingsClick
    val onManageSourcesClick = mainAppState.onManageSourcesClick
    val onGlobalTagBlacklistClick = mainAppState.onGlobalTagBlacklistClick
    val onTrackingAccountsClick = mainAppState.onTrackingAccountsClick
    val isAppUpdateAvailable = mainAppState.isAppUpdateAvailable
    val onAppUpdateClick = mainAppState.onAppUpdateClick
    val isIncognitoModeEnabled = mainAppState.isIncognitoModeEnabled
    val onIncognitoToggle = mainAppState.onIncognitoToggle
    val isLanguagePresetFilterVisible = mainAppState.isLanguagePresetFilterVisible
    val languagePresetEntries = mainAppState.languagePresetEntries
    val onLanguagePresetSelected = mainAppState.onLanguagePresetSelected
    val onManageLanguagePresets = mainAppState.onManageLanguagePresets
    val selectedContentType = mainAppState.selectedContentType
    val enabledContentTypes = mainAppState.enabledContentTypes
    val isContentTypeFilterVisible = mainAppState.isContentTypeFilterVisible
    val onContentTypeSelected = mainAppState.onContentTypeSelected
    val selectedSourceTags = mainAppState.selectedSourceTags
    val sourceTagEntries = mainAppState.sourceTagEntries
    val enabledSourceTags = mainAppState.enabledSourceTags
    val isSourceTagFilterVisible = mainAppState.isSourceTagFilterVisible
    val onSourceTagFilterClick = mainAppState.onSourceTagFilterClick
    val onSourceTagSelected = mainAppState.onSourceTagSelected
    val sourceTagCustomMenuContent = mainAppState.sourceTagCustomMenuContent
    val onTopBarHeightChanged = mainAppState.onTopBarHeightChanged
    val onBottomNavHeightChanged = mainAppState.onBottomNavHeightChanged
    val onContentInsetsChanged = mainAppState.onContentInsetsChanged
    val onNavDestinationChanged = mainAppState.onNavDestinationChanged
    val pendingSearchNavigation = mainAppState.pendingSearchNavigation
    val onSearchNavigationHandled = mainAppState.onSearchNavigationHandled
    val onFeedRefresh = mainAppState.onFeedRefresh
    val isResumeEnabled = mainAppState.isResumeEnabled
    val onResumeClick = mainAppState.onResumeClick
    val spaceUiState = mainAppState.spaceUiState
    val spaceTransitionState = mainAppState.spaceTransitionState
    val onSpaceTransitionCovered = mainAppState.onSpaceTransitionCovered
    val onSpaceCurtainCoverFinished = mainAppState.onSpaceCurtainCoverFinished
    val onSpaceCurtainRevealFinished = mainAppState.onSpaceCurtainRevealFinished
    val onSpaceAction = mainAppState.onSpaceAction
    val spaceNavigationSessionUiState = mainAppState.spaceNavigationSessionUiState
    val onSpaceSessionChanged = mainAppState.onSpaceSessionChanged
    val spaceTransitionSuppressionTarget = mainAppState.spaceTransitionSuppressionTarget
    val onSpaceTransitionSuppressionConsumed = mainAppState.onSpaceTransitionSuppressionConsumed
    val spaceResumeUiState = mainAppState.spaceResumeUiState
    val onSpaceResume = mainAppState.onSpaceResume

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val prefs = rememberKototoroAppPrefs(appSettings)
    val navigationPrefs by prefs.navigationPrefs
    val displayPrefs by prefs.displayPrefs
    val filterVisibilityPrefs by prefs.filterVisibilityPrefs
    val detailsTransitionStyle by prefs.detailsTransitionStyle
    val isReducedVisualEffectsEnabled by prefs.isReducedVisualEffectsEnabled
    val globalTagBlacklist by prefs.globalTagBlacklist
    val isNavBarPinned by prefs.isNavBarPinned
    val tabletUiMode by prefs.tabletUiMode
    val mainNavItems by prefs.mainNavItems
    val isMainFabEnabled by prefs.isMainFabEnabled
    val sidekickPosition by prefs.sidekickPosition
    val globalFavoritesSortOrder by prefs.globalFavoritesSortOrder
    val showAllUpdates by prefs.showAllUpdates
    val feedLimit by prefs.feedLimit
    val exitConfirmationEnabled by prefs.exitConfirmationEnabled
    val suppressSpaceContentMotion = spaceTransitionState.phase == SpaceTransitionPhase.COVERED ||
        spaceTransitionState.phase == SpaceTransitionPhase.REVEALING
    // Keep the shared transition scope STABLE across a space switch: the
    // space curtain handshake (COVERED/REVEALING) used to null the scope,
    // which tore the shared-element registration out of every cover at the
    // moment the curtain faded, so all card covers blinked out and re-set up.
    // No transition runs during the curtain (the v2 shell suppresses its own
    // enter/exit via suppressNavigationTransitions), so there is nothing to
    // double-animate -- only reduced-visual-effects should disable heroes.
    val effectiveSharedElementTransitionsEnabled = !isReducedVisualEffectsEnabled
    val spaceMotionMode = if (suppressSpaceContentMotion) {
        SpaceMotionMode.DISABLED
    } else {
        SpaceMotion.resolveMode(
            reducedVisualEffects = isReducedVisualEffectsEnabled,
            animatorDurationScale = context.animatorDurationScale,
        )
    }
    val isFloating = navigationPrefs.isFloating
    val isLayeredSurface = navigationPrefs.isLayeredSurface
    val activeSourcePresetId = displayPrefs.activeSourcePresetId
    val listMode = displayPrefs.listMode
    val browseListMode = displayPrefs.browseListMode
    val gridSize = displayPrefs.gridSize
    val cornerRadius = displayPrefs.cornerRadius
    val isBrowseTrackingRecommendationsEnabled = displayPrefs.isBrowseTrackingRecommendationsEnabled
    val isBrowseMoreTrackingRecommendationsEnabled = displayPrefs.isBrowseMoreTrackingRecommendationsEnabled
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

    val initialTopLevel = remember(mainNavItems) {
        topLevelKeyForBottomNavItem(mainNavItems.firstOrNull()?.id ?: org.skepsun.kototoro.R.id.nav_home)
    }
    val spaceNavigationStates = rememberSpaceNavigationStates(
        initialTopLevel = initialTopLevel,
        activeSpaceId = spaceUiState.activeSpaceId,
    )
    val navigationSpaceId = resolveNavigationSpaceId(
        activeSpaceId = spaceUiState.activeSpaceId,
        persistentNavigationEnabled = spaceUiState.persistentNavigationEnabled,
    )
    val activeNavigationState = spaceNavigationStates[navigationSpaceId]
    val mainNavState = activeNavigationState.mainNavState
    val chromeScrollStates = remember { mutableMapOf<SpaceId, SpaceChromeScrollState>() }
    val scrollToTopEventsBySpace = remember { mutableMapOf<SpaceId, MutableSharedFlow<Unit>>() }
    val chromeScrollState = chromeScrollStates.getOrPut(navigationSpaceId, ::SpaceChromeScrollState)
    val scrollToTopEvents = scrollToTopEventsBySpace.getOrPut(navigationSpaceId) {
        MutableSharedFlow(extraBufferCapacity = 1)
    }
    LaunchedEffect(spaceUiState.spaces) {
        val activeSpaceIds = spaceUiState.spaces.mapTo(mutableSetOf()) { it.id }
        chromeScrollStates.keys.retainAll(activeSpaceIds)
        scrollToTopEventsBySpace.keys.retainAll(activeSpaceIds)
    }

    var topBarHeightPx by chromeScrollState.topBarHeightPx
    var bottomNavHeightPx by remember { mutableIntStateOf(0) }
    var bottomNavOffset by chromeScrollState.bottomNavOffset
    var isLandscapeRailInteracting by remember { mutableStateOf(false) }
    val chromeState = rememberKototoroAppChromeState()
    val isSearchOverlayVisible by chromeState.isSearchOverlayVisible
    val isSearchOverlayMounted by chromeState.isSearchOverlayMounted
    val searchOverlayInitialQuery by chromeState.searchOverlayInitialQuery
    val isSearchOverlayQueryCommitted by chromeState.isSearchOverlayQueryCommitted
    val isDetailsChromeTransitionPending by chromeState.isDetailsChromeTransitionPending
    val detailsBottomPanelExpansion by chromeState.detailsBottomPanelExpansion
    val detailsBottomObstruction by chromeState.detailsBottomObstruction
    val detailsBottomPanelRoute by chromeState.detailsBottomPanelRoute
    val materialTopBarScrollEnabled by chromeState.materialTopBarScrollEnabled
    val lastChromeTopBarOwnerKey by chromeState.lastChromeTopBarOwnerKey
    val lastHeroTransitionStartedAtMs by chromeState.lastHeroTransitionStartedAtMs
    val heroTransitionPhase by chromeState.heroTransitionPhase
    val chromeSharedTransitionScope by chromeState.chromeSharedTransitionScope
    var rootContentBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var keepTabsExpandedByScrollDirection by chromeScrollState.keepTabsExpandedByScrollDirection
    val routeTopBarOverrideStates = remember { mutableStateMapOf<String, TopBarOverrideState>() }
    val routeContextualMenuActions = remember { mutableStateMapOf<String, List<KototoroTopBarMenuAction>>() }
    var globalTopBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var offsetDestinationRoute by chromeScrollState.offsetDestinationRoute
    var offsetDestinationOwnerKey by chromeScrollState.offsetDestinationOwnerKey

    val density = androidx.compose.ui.platform.LocalDensity.current
    val interfaceStyleTokens = LocalInterfaceStyleTokens.current
    val spaceSwitcherFabMargin = dimensionResource(R.dimen.space_switcher_fab_margin)
    val spaceSwitcherFabControlGap = dimensionResource(R.dimen.space_switcher_fab_control_gap)
    val statusBarHeightPx = with(density) {
        WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding().roundToPx()
    }
    val navigationBarHeightPx = with(density) {
        WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding().roundToPx()
    }
    val topAppBarState = chromeScrollState.topAppBarState
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
        navigationSpaceId,
    ) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (isSearchOverlayMounted) {
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                chromeScrollState.totalContentScrollOffset.floatValue =
                    (chromeScrollState.totalContentScrollOffset.floatValue + available.y).coerceAtMost(0f)
                if (isLandscapeRailInteracting) {
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

    val spaceSaveableStateHolder = rememberSaveableStateHolder()
    val restoredSpaceIds = remember { mutableStateMapOf<SpaceId, Boolean>() }
    val databaseRestoredSpaceIds = remember { mutableStateMapOf<SpaceId, Boolean>() }
    val rootRestoredSpaceIds = remember { mutableStateMapOf<SpaceId, Boolean>() }
    val isActiveSpaceRestored = restoredSpaceIds[navigationSpaceId] == true
    val isActiveDatabaseSessionApplied = databaseRestoredSpaceIds[navigationSpaceId] == true
    val isActiveNavigationReady = !spaceNavigationSessionUiState.enabled || isActiveSpaceRestored
    KototoroAppSpaceSessionEffects(
        onSpaceSessionChanged = onSpaceSessionChanged,
        spaceNavigationSessionUiState = spaceNavigationSessionUiState,
        spaceUiState = spaceUiState,
        spaceNavigationStates = spaceNavigationStates,
        navigationSpaceId = navigationSpaceId,
        initialTopLevel = initialTopLevel,
        restoredSpaceIds = restoredSpaceIds,
        databaseRestoredSpaceIds = databaseRestoredSpaceIds,
        rootRestoredSpaceIds = rootRestoredSpaceIds,
        spaceTransitionState = spaceTransitionState,
        isActiveNavigationReady = isActiveNavigationReady,
        isActiveSpaceRestored = isActiveSpaceRestored,
        isActiveDatabaseSessionApplied = isActiveDatabaseSessionApplied,
        mainNavState = mainNavState,
        spaceTransitionSuppressionTarget = spaceTransitionSuppressionTarget,
        onSpaceTransitionCovered = onSpaceTransitionCovered,
        onSpaceTransitionSuppressionConsumed = onSpaceTransitionSuppressionConsumed,
    )
    val topLevelNavigator: MainNavigator = remember(mainNavState) {
        MainStateNavigator(
            mainActivity = null,
            mainNavState = mainNavState,
        )
    }
    fun navigateToBottomNavItem(itemId: Int) {
        val topLevelKey = topLevelKeyForBottomNavItem(itemId)
        if (mainNavState.selectedTopLevel != topLevelKey) {
            topLevelNavigator.openTopLevel(topLevelKey)
        }
    }
    val currentBottomNavNavigationState = rememberUpdatedState(activeNavigationState)
    val currentScrollToTopEvents = rememberUpdatedState(scrollToTopEvents)
    val bottomNavDispatcher = remember {
        { itemId: Int ->
            val navigationState = currentBottomNavNavigationState.value
            val topLevelKey = topLevelKeyForBottomNavItem(itemId)
            if (navigationState.mainNavState.selectedTopLevel != topLevelKey) {
                MainStateNavigator(
                    mainActivity = null,
                    mainNavState = navigationState.mainNavState,
                ).openTopLevel(topLevelKey)
            }
        }
    }
    val bottomNavReselectionDispatcher = remember {
        { _: Int ->
            currentScrollToTopEvents.value.tryEmit(Unit)
            Unit
        }
    }
    val currentNavTopEntry: org.skepsun.kototoro.main.ui.navigation3.MainNavKey? =
        mainNavState.currentStack().lastOrNull()
    val currentDestinationRoute = when (currentNavTopEntry) {
        is ContentListNavKey -> "content_list"
        is DetailsNavKey -> "details"
        is SearchNavKey -> "search"
        else -> AppRouteNames.MAIN_SHELL
    }
    val isSearchRoute = currentNavTopEntry is SearchNavKey
    val isDetailsRoute = currentNavTopEntry is DetailsNavKey
    val isContentListRoute = currentNavTopEntry is ContentListNavKey
    val isImmersiveRoute = isDetailsRoute || isContentListRoute
    val shouldShowChrome = !isSearchRoute && !isImmersiveRoute

    val mainShellBackdropOwnerKey = remember(navigationSpaceId) {
        "$MAIN_SHELL_BACKDROP_OWNER_PREFIX:${navigationSpaceId.value}"
    }

    val activeSpaceResumeItem = spaceResumeUiState.items[spaceUiState.activeSpaceId]
    val effectiveResumeContent = if (spaceUiState.switcherEnabled) {
        activeSpaceResumeItem?.content
    } else {
        lastReadContent
    }
    val effectiveResumeContentType = if (spaceUiState.switcherEnabled) {
        when (spaceUiState.spaces.firstOrNull { it.id == spaceUiState.activeSpaceId }?.kind) {
            SpaceKind.MANGA -> ContentType.MANGA
            SpaceKind.NOVEL -> ContentType.NOVEL
            SpaceKind.ANIME -> ContentType.VIDEO
            null -> effectiveResumeContent?.source?.getContentType()
        }
    } else {
        effectiveResumeContent?.source?.getContentType()
    }
    val effectiveResumeAction = resolveMainResumeAction(
        contentType = effectiveResumeContentType,
        looksLikeVideoContent = effectiveResumeContent?.looksLikeLocalVideoContent() == true,
    )
    val effectiveResumeCoverModel = rememberMainResumeCoverRequest(effectiveResumeContent)
    val effectiveResumeEnabled = isMainFabEnabled && if (spaceUiState.switcherEnabled) {
        activeSpaceResumeItem?.canResume == true
    } else {
        isResumeEnabled
    }
    val effectiveResumeClick = if (spaceUiState.switcherEnabled) {
        { onSpaceResume(spaceUiState.activeSpaceId) }
    } else {
        onResumeClick
    }
    val currentTopLevelKey = if (shouldShowChrome) mainNavState.selectedTopLevel else null
    val currentTopBarOwnerKey = routeOwnerKeyForTopLevelKey(currentTopLevelKey)
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
    val shouldHideChromeForEnteringDetails =
        isDetailsChromeTransitionPending && heroTransitionPhase == HeroTransitionPhase.EnteringDetails
    val shouldDelayChromeRestoreFromDetails =
        pendingChromeRestoreFromDetails && shouldShowChrome && !isImmersiveRoute
    LaunchedEffect(shouldShowChrome, isImmersiveRoute, isDetailsChromeTransitionPending) {
        fun clearChromeTransitionFlags(clearPendingRestore: Boolean = true) {
            if (clearPendingRestore) {
                pendingChromeRestoreFromDetails = false
            }
            chromeState.setDetailsChromeTransitionPending(false)
        }
        when {
            shouldHideChromeForEnteringDetails -> {
                isChromeVisible = false
                pendingChromeRestoreFromDetails = false
            }
            isImmersiveRoute -> {
                pendingChromeRestoreFromDetails = true
                isChromeVisible = false
                if (!isDetailsChromeTransitionPending) {
                    return@LaunchedEffect
                }
                delay(MainNavigationMotion.ChromeEnterExitDelayMillis)
                chromeState.setDetailsChromeTransitionPending(false)
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
            chromeState.setHeroTransitionPhase(HeroTransitionPhase.Idle)
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
    val isChromeOffsetFromCurrentDestination =
        offsetDestinationRoute == currentDestinationRoute && offsetDestinationOwnerKey == currentTopBarOwnerKey
    val effectiveTopBarOffset = if (isChromeOffsetFromCurrentDestination && shouldUseMaterialTopBarScroll) {
        topAppBarState.heightOffset
    } else {
        0f
    }
    val effectiveBottomNavOffset = if (isChromeOffsetFromCurrentDestination) bottomNavOffset else 0f
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
        animationSpec = if (suppressSpaceContentMotion) {
            snap()
        } else {
            tween(durationMillis = MainNavigationMotion.ChromeAlphaMillis)
        },
        label = "chrome_alpha",
    )
    val chromeAlpha = if (suppressSpaceContentMotion) effectiveChromeAlphaTarget else animatedChromeAlpha
    val isHomeRoute = chromeTopLevelKey == HomeNavKey
    val supportsDisplayModeMenu = chromeTopLevelKey.supportsDisplayModeMenu()
    val supportsGridSizeSlider = chromeTopLevelKey.supportsGridSizeSlider()
    val isFavoritesRoute = chromeTopLevelKey == FavoritesNavKey
    val fallbackFavoritesSortOrders = if (isFavoritesRoute) ListSortOrder.FAVORITES.sortedByOrdinal() else emptyList()
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


    val reservedTopBarHeightPx = maxOf(
        topBarHeightPx,
        statusBarHeightPx + with(density) { interfaceStyleTokens.mainTopBarHeight.roundToPx() },
    )
    val maxCollapsePx = (reservedTopBarHeightPx - statusBarHeightPx).coerceAtLeast(0)
    val contentTopInsetPx = if (shouldReserveChromeInsets) {
        (reservedTopBarHeightPx + effectiveTopBarOffset).toInt()
            .coerceIn(maxCollapsePx, reservedTopBarHeightPx)
    } else {
        0
    }
    val layoutDirection = LocalLayoutDirection.current
    val displayCutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val displayCutoutStartDp = displayCutoutPadding.calculateStartPadding(layoutDirection)
    val displayCutoutEndDp = displayCutoutPadding.calculateEndPadding(layoutDirection)
    val applyRootDisplayCutoutPadding = !isDetailsRoute
    val rootDisplayCutoutStartDp = if (applyRootDisplayCutoutPadding) displayCutoutStartDp else 0.dp
    val rootDisplayCutoutEndDp = if (applyRootDisplayCutoutPadding) displayCutoutEndDp else 0.dp
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

    val contentPadding = remember(contentTopInsetPx, contentBottomInsetPx, density) {
        with(density) {
            androidx.compose.foundation.layout.PaddingValues(
                top = contentTopInsetPx.toDp(),
                bottom = contentBottomInsetPx.toDp()
            )
        }
    }


    val mainSpaceSwitcherFabBounds by KototoroAppChromeEffects(
        chromeState = chromeState,
        chromeScrollState = chromeScrollState,
        shouldShowChrome = shouldShowChrome,
        isImmersiveRoute = isImmersiveRoute,
        isDetailsRoute = isDetailsRoute,
        isContentListRoute = isContentListRoute,
        isSearchRoute = isSearchRoute,
        currentDestinationRoute = currentDestinationRoute,
        currentTopBarOwnerKey = currentTopBarOwnerKey,
        currentTopLevelKey = currentTopLevelKey,
        shouldUseMaterialTopBarScroll = shouldUseMaterialTopBarScroll,
        isChromeOffsetFromCurrentDestination = isChromeOffsetFromCurrentDestination,
        navigationSpaceId = navigationSpaceId,
        isLandscapeNavigation = isLandscapeNavigation,
        mainNavState = mainNavState,
        isActiveSpaceRestored = isActiveSpaceRestored,
        contentTopInsetPx = contentTopInsetPx,
        contentBottomInsetPx = contentBottomInsetPx,
        effectiveTopBarOffset = effectiveTopBarOffset,
        effectiveBottomNavOffset = effectiveBottomNavOffset,
        onNavDestinationChanged = onNavDestinationChanged,
        onContentInsetsChanged = onContentInsetsChanged,
    )

    KototoroTheme(cornerRadius = cornerRadius) {
        val liquidGlassBackdropHost = remember { LiquidGlassBackdropHost() }
        val rootGlassMenuHost = remember { RootGlassMenuHost() }
        val expectedLiquidGlassOwnerKey = mainShellBackdropOwnerKey
        val activeLiquidGlassBackdrop = liquidGlassBackdropHost.backdropFor(expectedLiquidGlassOwnerKey)
        val glassPrefs = rememberGlassPrefs(appSettings)
        val railAnimationFactor = rememberRailAnimationFactor(appSettings)
        val motionStyle = LocalMotionStyle.current
        val surfaceStyle = LocalSurfaceStyle.current
        // Content-overlap under the floating top chrome (0f at the top, 1f once the list has
        // scrolled at least one top-bar-height). Deliberately position-derived (never velocity),
        // smoothed with a short tween (Material: snappy, iOS: slightly longer).
        val rawChromeScrollOverlap = if (topBarHeightPx > 0) {
            (-chromeScrollState.totalContentScrollOffset.floatValue / topBarHeightPx.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val overlapTweenMillis = if (motionStyle == MotionStyle.IOS) 240 else 120
        val chromeScrollOverlap by animateFloatAsState(
            targetValue = rawChromeScrollOverlap,
            animationSpec = tween(durationMillis = overlapTweenMillis),
            label = "chrome_scroll_overlap",
        )
        CompositionLocalProvider(
            LocalLiquidGlassBackdrop provides activeLiquidGlassBackdrop,
            LocalLiquidGlassLayerBackdrop provides activeLiquidGlassBackdrop,
            LocalLiquidGlassBackdropHost provides liquidGlassBackdropHost,
            LocalRootGlassMenuHost provides rootGlassMenuHost,
            LocalGlassPrefs provides glassPrefs,
            LocalRailAnimationFactor provides railAnimationFactor,
            LocalChromeScrollOverlap provides chromeScrollOverlap,
        ) {
            val immersiveStrength = ((LocalGlassPrefs.current?.immersiveStrengthPercent ?: 65).coerceIn(0, 100)) / 100f
            val immersiveBaseColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
            val immersiveTransparent = immersiveBaseColor.toTransparentImmersiveColor()
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
            val spaceSwitcherFabSize = 56.dp
            val spaceSwitcherFabBaseBottom = WindowInsets.safeDrawing
                .asPaddingValues()
                .calculateBottomPadding() + spaceSwitcherFabMargin
            val rootBounds = rootContentBounds
            val mainAnchorBounds = mainSpaceSwitcherFabBounds
            val shouldAnchorSpaceSwitcherFabToMainChrome = shouldShowChrome && !isLandscapeNavigation
            val spaceSwitcherFabTargetOffset = rootBounds?.let { bounds ->
                if (shouldAnchorSpaceSwitcherFabToMainChrome && mainAnchorBounds != null) {
                    val anchorBounds = mainAnchorBounds
                    androidx.compose.ui.unit.IntOffset(
                        x = (anchorBounds.left - bounds.left).roundToInt(),
                        y = (anchorBounds.top - bounds.top).roundToInt(),
                    )
                } else {
                    val detailsLift = if (isDetailsRoute && !isLandscapeNavigation) {
                        (detailsBottomObstruction + spaceSwitcherFabControlGap - spaceSwitcherFabBaseBottom)
                            .coerceAtLeast(0.dp)
                    } else {
                        0.dp
                    }
                    androidx.compose.ui.unit.IntOffset(
                        x = (bounds.width - with(density) {
                            displayCutoutEndDp.roundToPx() +
                                (if (isDetailsRoute) {
                                    CompactTopBarHorizontalPadding
                                } else {
                                    spaceSwitcherFabMargin
                                }).roundToPx() +
                                spaceSwitcherFabSize.roundToPx()
                        }).roundToInt(),
                        y = (bounds.height - with(density) {
                            spaceSwitcherFabBaseBottom.roundToPx() +
                                detailsLift.roundToPx() +
                                spaceSwitcherFabSize.roundToPx()
                        }).roundToInt(),
                    )
                }
            }
            var lastValidSpaceSwitcherFabTarget by remember { mutableStateOf<androidx.compose.ui.unit.IntOffset?>(null) }
            LaunchedEffect(spaceSwitcherFabTargetOffset) {
                if (spaceSwitcherFabTargetOffset != null) {
                    lastValidSpaceSwitcherFabTarget = spaceSwitcherFabTargetOffset
                }
            }
            val mainShellChrome: @Composable BoxScope.() -> Unit = {
                if (shouldShowChrome || isChromeVisible || chromeAlpha > 0f) {
                    if (!isImmersiveRoute) {
                        ImmersiveEdgeGradient(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val contentScrollAlpha = if (topBarHeightPx > 0) {
                                        (-chromeScrollState.totalContentScrollOffset.floatValue / topBarHeightPx.toFloat())
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    alpha = if (surfaceStyle == SurfaceStyle.BACKDROP) {
                                        // iOS: the edge fade is overlap-driven (0 at the top, 1 once
                                        // content passes under the floating chrome), gated by chrome
                                        // visibility. Never velocity-driven.
                                        chromeScrollOverlap * chromeAlpha
                                    } else {
                                        resolveTopImmersiveAlpha(
                                            contentScrollAlpha = contentScrollAlpha,
                                            chromeAlpha = chromeAlpha,
                                        )
                                    }
                                },
                            height = topImmersiveHeight + ImmersiveEdgeFeatherExtension,
                            colors = if (surfaceStyle == SurfaceStyle.BACKDROP) {
                                // iOS: backdrop strength ramps 0.65 → 1.0 and the scrim lifts
                                // 0.02 → 0.08 as overlap grows under the floating chrome.
                                val overlap = chromeScrollOverlap
                                val backdropStrength = immersiveStrength * lerpFloat(0.65f, 1f, overlap)
                                val scrimLift = lerpFloat(0.02f, 0.08f, overlap)
                                listOf(
                                    immersiveBaseColor.copy(alpha = (lerpFloat(0.72f, 0.98f, backdropStrength) + scrimLift).coerceIn(0f, 1f)),
                                    immersiveBaseColor.copy(alpha = (lerpFloat(0.56f, 0.82f, backdropStrength) + scrimLift).coerceIn(0f, 1f)),
                                    immersiveBaseColor.copy(alpha = (lerpFloat(0.32f, 0.52f, backdropStrength) + scrimLift).coerceIn(0f, 1f)),
                                    immersiveBaseColor.copy(alpha = (lerpFloat(0.12f, 0.22f, backdropStrength) + scrimLift).coerceIn(0f, 1f)),
                                    immersiveTransparent,
                                )
                            } else {
                                // Material: stable tonal surface — strength and scrim stay put,
                                // only whole-gradient alpha responds with the short chrome tween.
                                listOf(
                                    immersiveBaseColor.copy(alpha = lerpFloat(0.72f, 0.98f, immersiveStrength)),
                                    immersiveBaseColor.copy(alpha = lerpFloat(0.56f, 0.82f, immersiveStrength)),
                                    immersiveBaseColor.copy(alpha = lerpFloat(0.32f, 0.52f, immersiveStrength)),
                                    immersiveBaseColor.copy(alpha = lerpFloat(0.12f, 0.22f, immersiveStrength)),
                                    immersiveTransparent,
                                )
                            },
                            stops = ImmersiveTopGradientStops,
                        )

                        ImmersiveEdgeGradient(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            height = bottomImmersiveHeight + ImmersiveEdgeFeatherExtension,
                            colors = listOf(
                                immersiveTransparent,
                                immersiveBaseColor.copy(alpha = lerpFloat(0.14f, 0.24f, immersiveStrength)),
                                immersiveBaseColor.copy(alpha = lerpFloat(0.34f, 0.54f, immersiveStrength)),
                                immersiveBaseColor.copy(alpha = lerpFloat(0.60f, 0.90f, immersiveStrength)),
                            ),
                            stops = ImmersiveBottomGradientStops,
                        )
                    }

                    MainTopChrome(
                        effectiveTopBarOverrideState = effectiveTopBarOverrideState,
                        isLandscapeNavigation = isLandscapeNavigation,
                        isLayeredSurface = isLayeredSurface,
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
                            chromeState.setSearchOverlayInitialQuery(query)
                            chromeState.setSearchOverlayQueryCommitted(false)
                            chromeState.setSearchOverlayMounted(true)
                            chromeState.setSearchOverlayVisible(true)
                        },
                        onOpenListOptions = onOpenListOptions,
                        onDisplayOptionsClick = if (isHomeRoute) onHomeDisplayOptionsClick else null,
                        onSettingsClick = onSettingsClick,
                        onHelpClick = onHelpClick,
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
                        sourceTagCustomMenuContent = sourceTagCustomMenuContent,
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
                        isLayeredSurface = isLayeredSurface,
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
                        onItemSelected = bottomNavDispatcher,
                        onItemReselected = bottomNavReselectionDispatcher,
                        isResumeEnabled = effectiveResumeEnabled,
                        onResumeClick = effectiveResumeClick,
                        resumeAction = effectiveResumeAction,
                        resumeCoverModel = effectiveResumeCoverModel,
                        railHeaderContent = null,
                        adjacentAction = if (!isLandscapeNavigation && effectiveResumeEnabled) {
                            {
                                ContinueReadingFab(
                                    onClick = effectiveResumeClick,
                                    action = effectiveResumeAction,
                                    coverModel = effectiveResumeCoverModel,
                                    size = prefs.navigationPrefs.value.adjacentFabSize,
                                )
                            }
                        } else null,
                    )
                }
            }
            DynamicArtworkBackdrop(
                content = lastReadContent,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    .nestedScroll(nestedScrollConnection)
                    .onGloballyPositioned { coordinates ->
                        rootContentBounds = coordinates.boundsInRoot()
                    },
            ) {
                if (shouldShowChrome && isLandscapeNavigation && displayCutoutStartDp > 0.dp) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(displayCutoutStartDp),
                        color = NavigationRailDefaults.ContainerColor,
                        tonalElevation = 3.dp,
                    ) {}
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = rootDisplayCutoutStartDp, end = rootDisplayCutoutEndDp),
                ) {
                    SharedTransitionLayout {
                    SideEffect {
                        chromeState.setChromeSharedTransitionScope(
                            if (effectiveSharedElementTransitionsEnabled) {
                                this@SharedTransitionLayout
                            } else {
                                null
                            },
                        )
                    }
                    CompositionLocalProvider(
                        // `…InProgress` stays false on purpose: it gates the details-cover
                        // freeze in DetailsCoverFrame, parked back since 1f70d606a. The
                        // phase itself is delivered so the home hero carousel can hold the
                        // flying card's decorative transform still for the hero window.
                        LocalHeroTransitionInProgress provides false,
                        LocalHeroReturnTransitionInProgress provides false,
                        LocalHeroTransitionPhase provides heroTransitionPhase,
                        LocalSharedTransitionScope provides if (effectiveSharedElementTransitionsEnabled) {
                            this@SharedTransitionLayout
                        } else {
                            null
                        },
                    ) {
                        val renderSpaceNavigation: @Composable (SpaceId) -> Unit = { renderedSpaceId ->
                            val renderedNavigationState = spaceNavigationStates[renderedSpaceId]
                            spaceSaveableStateHolder.SaveableStateProvider(renderedSpaceId.value) {
                                CompositionLocalProvider(
                                    LocalBrowseSpaceId provides renderedSpaceId.takeIf {
                                        spaceUiState.switcherEnabled
                                    },
                                    LocalScrollToTopEvents provides scrollToTopEventsBySpace.getOrPut(renderedSpaceId) {
                                        MutableSharedFlow(extraBufferCapacity = 1)
                                    },
                                ) {
                                    MainShellScene(
                                        mainNavState = renderedNavigationState.mainNavState,
                                        shellBackdropOwnerKey = "$MAIN_SHELL_BACKDROP_OWNER_PREFIX:${renderedSpaceId.value}",
                                        detailsTransitionStyle = detailsTransitionStyle,
                                        isLandscapeNavigation = isLandscapeNavigation,
                                        contentPadding = contentPadding,
                                        bottomBarOffsetPx = effectiveBottomNavOffset,
                                        bottomBarHeightPx = bottomNavHeightPx,
                                        pageSaveHelper = pageSaveHelper,
                                        onDetailsTransitionRequested = {
                                            chromeState.setDetailsChromeTransitionPending(true)
                                            chromeState.setHeroTransitionPhase(HeroTransitionPhase.EnteringDetails)
                                            chromeState.setLastHeroTransitionStartedAtMs(heroTransitionTimestampMs())
                                        },
                                        // Mirrors onDetailsTransitionRequested for the pop direction: flags the
                                        // return hero so the details cover freezes on the cached painter and the
                                        // chrome restore waits for HeroProtectionMillis instead of racing the
                                        // shared-element settle. Dropped in the nav3 flattening (e637a98fd), which
                                        // left the return cover un-frozen and visibly two-stepped.
                                        onDetailsReturnTransitionRequested = {
                                            if (effectiveSharedElementTransitionsEnabled) {
                                                chromeState.setDetailsChromeTransitionPending(true)
                                                chromeState.setHeroTransitionPhase(HeroTransitionPhase.ReturningFromDetails)
                                                chromeState.setLastHeroTransitionStartedAtMs(heroTransitionTimestampMs())
                                            }
                                        },
                                        onDetailsBottomPanelStateChanged = { expansion, obstruction ->
                                            if (renderedSpaceId == navigationSpaceId) {
                                                chromeState.setDetailsBottomPanelRoute(currentDestinationRoute)
                                                chromeState.setDetailsBottomPanelExpansion(expansion)
                                                chromeState.setDetailsBottomObstruction(obstruction)
                                            }
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
                                                    } else if (!overrideStateEquivalent(routeTopBarOverrideStates[ownerRoute], state)) {
                                                        // Semantic, not data-class ==: the routes re-report from an
                                                        // unkeyed SideEffect (history) or a re-remembered DisposableEffect
                                                        // (favourites) and hand us a freshly built state every
                                                        // recomposition, callback lambdas included. == on those states
                                                        // therefore never dedupes, and unlike MutableState.setValue a
                                                        // snapshot map notifies its readers on every put even when the
                                                        // value is equal, so an identity/== check here turned "report
                                                        // again" into a permanent invalidate -> recompose -> report loop
                                                        // (~620 frames per idle 5s on an untouched history screen;
                                                        // see overrideStateEquivalent).
                                                        routeTopBarOverrideStates[ownerRoute] = state
                                                    }
                                                }
                                                else -> {
                                                    if (!overrideStateEquivalent(globalTopBarOverrideState, overrideState)) {
                                                        globalTopBarOverrideState = overrideState
                                                    }
                                                }
                                            }
                                        },
                                        onContextualMenuActionsChanged = { state ->
                                            // Same snapshot-collection rule as the top-bar override above: only touch
                                            // the map when it would actually change.
                                            if (state.actions.isEmpty()) {
                                                if (state.ownerRoute in routeContextualMenuActions) {
                                                    routeContextualMenuActions.remove(state.ownerRoute)
                                                }
                                            } else if (!menuActionsEquivalent(routeContextualMenuActions[state.ownerRoute].orEmpty(), state.actions)) {
                                                // See overrideStateEquivalent: the action lists carry freshly built
                                                // onClick callbacks on every re-report, so == never dedupes and every
                                                // put re-invalidates the shell that reads this map.
                                                routeContextualMenuActions[state.ownerRoute] = state.actions
                                            }
                                        },
                                        onOpenSearch = { request ->
                                            topLevelNavigator.openSearch(request)
                                        },
                                        mainShellChrome = {
                                            if (renderedSpaceId == navigationSpaceId) {
                                                mainShellChrome()
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        if (isActiveNavigationReady) {
                            key(navigationSpaceId.value) {
                                renderSpaceNavigation(navigationSpaceId)
                            }
                        }
                    }
                }
                SpaceSidekick(
                    state = spaceUiState,
                    onAction = onSpaceAction,
                    resumeItems = spaceResumeUiState.items,
                    onResume = onSpaceResume,
                    position = sidekickPosition,
                    visible = spaceUiState.switcherEnabled &&
                        (shouldShowChrome || isImmersiveRoute || isSearchRoute) &&
                        (!isDetailsRoute ||
                            detailsBottomPanelRoute != currentDestinationRoute ||
                            detailsBottomPanelExpansion <= 0.01f),
                    modifier = Modifier.matchParentSize(),
                )
                RootGlassMenuOverlay(
                    host = rootGlassMenuHost,
                    modifier = Modifier.matchParentSize(),
                )
                LaunchedEffect(
                    spaceUiState.switcherEnabled,
                    isDetailsRoute,
                    spaceSwitcherFabTargetOffset,
                    density,
                ) {
                    if (
                        spaceUiState.switcherEnabled &&
                        isDetailsRoute &&
                        spaceSwitcherFabTargetOffset != null
                    ) {
                        val halfFabSize = with(density) { spaceSwitcherFabSize.toPx() / 2f }
                        ImmersiveSpaceSwitcherTransition.updateDetailsOrigin(
                            centerX = spaceSwitcherFabTargetOffset.x + halfFabSize,
                            centerY = spaceSwitcherFabTargetOffset.y + halfFabSize,
                        )
                    } else {
                        ImmersiveSpaceSwitcherTransition.clearDetailsOrigin()
                    }
                }
                LaunchedEffect(
                    navigationSpaceId,
                    currentDestinationRoute,
                    spaceSwitcherFabTargetOffset,
                ) {
                    traceSpaceFab {
                        "target changed space=${navigationSpaceId.value} route=$currentDestinationRoute " +
                            "target=$spaceSwitcherFabTargetOffset anchor=$mainAnchorBounds root=$rootBounds"
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
                        blacklistedTagCount = globalTagBlacklist.size,
                        onQueryChanged = onQueryChanged,
                        onSearch = {
                            chromeState.setSearchOverlayQueryCommitted(true)
                            onSearch(it)
                            chromeState.setSearchOverlayVisible(false)
                        },
                        onSearchWithOptions = { searchQuery, kind, sourceTypes, contentKinds, advancedQuery, pinnedOnly, hideEmpty ->
                            chromeState.setSearchOverlayQueryCommitted(true)
                            onSearchWithOptions(
                                searchQuery,
                                kind,
                                sourceTypes,
                                contentKinds,
                                advancedQuery,
                                pinnedOnly,
                                hideEmpty,
                            )
                            chromeState.setSearchOverlayVisible(false)
                        },
                        onDismissRequest = { chromeState.setSearchOverlayVisible(false) },
                        onLanguagePresetSelected = onLanguagePresetSelected,
                        onManageLanguagePresets = onManageLanguagePresets,
                        onOpenGlobalTagBlacklist = {
                            onGlobalTagBlacklistClick()
                        },
                        onExitFinished = {
                            if (!isSearchOverlayVisible) {
                                if (!isSearchOverlayQueryCommitted) {
                                    onQueryChanged(searchOverlayInitialQuery)
                                }
                                chromeState.setSearchOverlayMounted(false)
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
                            chromeState.setSearchOverlayVisible(false)
                        },
                        onSourceSuggestionClick = {
                            onSourceSuggestionClick(it)
                            chromeState.setSearchOverlayVisible(false)
                        },
                        onAuthorSuggestionClick = {
                            onAuthorSuggestionClick(it)
                            chromeState.setSearchOverlayVisible(false)
                        },
                        onDeleteQuery = onDeleteQuery,
                        onVoiceInput = onVoiceInput,
                    )
                }
                SpaceTransitionCurtain(
                    state = spaceTransitionState,
                    spaces = spaceUiState.spaces,
                    // Above the SpaceSidekick panel (zIndex 20) so the switch
                    // curtain actually covers the drawer covers while the panel
                    // slides away -- otherwise the panel stays visible on top of
                    // the opaque curtain and every cover hiccup reads as a flicker.
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(30f),
                    isTargetHost = spaceTransitionState.targetSpaceId == navigationSpaceId,
                    allowReveal = isSpaceCurtainRevealHost(
                        targetSpaceId = spaceTransitionState.targetSpaceId,
                        hostSpaceId = navigationSpaceId,
                        activeSpaceId = spaceUiState.activeSpaceId,
                    ),
                    onCoverFinished = onSpaceCurtainCoverFinished,
                    onRevealFinished = onSpaceCurtainRevealFinished,
                )
                }
            }
        }
    }

    LaunchedEffect(pendingSearchNavigation?.requestId) {
        val request = pendingSearchNavigation ?: return@LaunchedEffect
        topLevelNavigator.openSearch(request)
        onSearchNavigationHandled()
    }


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


@Composable
private fun KototoroAppSpaceSessionEffects(
    onSpaceSessionChanged: (SpaceSessionSnapshot) -> Unit,
    spaceNavigationSessionUiState: SpaceNavigationSessionUiState,
    spaceUiState: SpaceUiState,
    spaceNavigationStates: org.skepsun.kototoro.main.ui.navigation3.SpaceNavigationStates,
    navigationSpaceId: SpaceId,
    initialTopLevel: TopLevelNavKey,
    restoredSpaceIds: MutableMap<SpaceId, Boolean>,
    databaseRestoredSpaceIds: MutableMap<SpaceId, Boolean>,
    rootRestoredSpaceIds: MutableMap<SpaceId, Boolean>,
    spaceTransitionState: SpaceTransitionState,
    isActiveNavigationReady: Boolean,
    isActiveSpaceRestored: Boolean,
    isActiveDatabaseSessionApplied: Boolean,
    mainNavState: org.skepsun.kototoro.main.ui.navigation3.MainNavState,
    spaceTransitionSuppressionTarget: SpaceId?,
    onSpaceTransitionCovered: suspend (SpaceId) -> Unit,
    onSpaceTransitionSuppressionConsumed: (SpaceId) -> Unit,
) {
    val currentOnSpaceSessionChanged by rememberUpdatedState(onSpaceSessionChanged)
    LaunchedEffect(
        spaceNavigationSessionUiState.enabled,
        spaceNavigationSessionUiState.restorationReady,
        spaceNavigationSessionUiState.sessions,
        // A custom navigation state is created lazily when that Space becomes active. Re-run
        // restoration after switching to it; otherwise the initial built-in Space pass skips it
        // and the covered Space transition can never become ready to reveal.
        navigationSpaceId,
    ) {
        if (!spaceNavigationSessionUiState.enabled) {
            restoredSpaceIds.clear()
            databaseRestoredSpaceIds.clear()
            rootRestoredSpaceIds.clear()
            return@LaunchedEffect
        }
        if (!spaceNavigationSessionUiState.restorationReady) return@LaunchedEffect
        spaceUiState.spaces.forEach { context ->
            if (context.id !in spaceNavigationStates) return@forEach
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
    LaunchedEffect(
        spaceTransitionState.phase,
        spaceTransitionState.targetSpaceId,
        navigationSpaceId,
        isActiveNavigationReady,
    ) {
        if (
            spaceTransitionState.phase == SpaceTransitionPhase.COVERED &&
            spaceTransitionState.targetSpaceId == navigationSpaceId &&
            isActiveNavigationReady
        ) {
            androidx.compose.runtime.withFrameNanos { }
            onSpaceTransitionCovered(navigationSpaceId)
        }
    }
    LaunchedEffect(
        navigationSpaceId,
        isActiveNavigationReady,
        spaceTransitionSuppressionTarget,
    ) {
        if (isActiveNavigationReady && spaceTransitionSuppressionTarget == navigationSpaceId) {
            androidx.compose.runtime.withFrameNanos { }
            onSpaceTransitionSuppressionConsumed(navigationSpaceId)
        }
    }
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
    LaunchedEffect(
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
        // The v3 back stacks were already populated by restoreFromSpaceSession; the inner
        // NavDisplay renders their immersive entries directly. No pending details origin is
        // seeded here: every restored DetailsNavKey re-seeds its own origin from the key's
        // identity (MainShellScene's DetailsNavKey branch) synchronously before its fresh
        // ViewModels are created. Seeding in this LaunchedEffect would run after that first
        // composition and leave a stale payload behind that a different details entry could
        // later consume.
        rootRestoredSpaceIds[navigationSpaceId] = true
    }
}

@Composable
private fun KototoroAppChromeEffects(
    chromeState: KototoroAppChromeState,
    chromeScrollState: SpaceChromeScrollState,
    shouldShowChrome: Boolean,
    isImmersiveRoute: Boolean,
    isDetailsRoute: Boolean,
    isContentListRoute: Boolean,
    isSearchRoute: Boolean,
    currentDestinationRoute: String?,
    currentTopBarOwnerKey: String?,
    currentTopLevelKey: TopLevelNavKey?,
    shouldUseMaterialTopBarScroll: Boolean,
    isChromeOffsetFromCurrentDestination: Boolean,
    navigationSpaceId: SpaceId,
    isLandscapeNavigation: Boolean,
    mainNavState: org.skepsun.kototoro.main.ui.navigation3.MainNavState,
    isActiveSpaceRestored: Boolean,
    contentTopInsetPx: Int,
    contentBottomInsetPx: Int,
    effectiveTopBarOffset: Float,
    effectiveBottomNavOffset: Float,
    onNavDestinationChanged: (Int) -> Unit,
    onContentInsetsChanged: (Int, Int) -> Unit,
): androidx.compose.runtime.State<androidx.compose.ui.geometry.Rect?> {
    val mainSpaceSwitcherFabBoundsState = remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }
    var mainSpaceSwitcherFabBounds by mainSpaceSwitcherFabBoundsState
    var canMeasureMainSpaceSwitcherFab by remember { mutableStateOf(true) }
    var mainSpaceSwitcherFabMeasurementSpaceId by remember { mutableStateOf(navigationSpaceId) }
    var mainSpaceSwitcherFabCandidate by remember {
        mutableStateOf<Pair<SpaceId, androidx.compose.ui.geometry.Rect>?>(null)
    }
    val isSearchOverlayMounted by chromeState.isSearchOverlayMounted
    val isSearchOverlayVisible by chromeState.isSearchOverlayVisible
    val isDetailsChromeTransitionPending by chromeState.isDetailsChromeTransitionPending
    val materialTopBarScrollEnabled by chromeState.materialTopBarScrollEnabled
    val lastChromeTopBarOwnerKey by chromeState.lastChromeTopBarOwnerKey
    var topAppBarState = chromeScrollState.topAppBarState
    var topBarHeightPx by chromeScrollState.topBarHeightPx
    var bottomNavOffset by chromeScrollState.bottomNavOffset
    var keepTabsExpandedByScrollDirection by chromeScrollState.keepTabsExpandedByScrollDirection
    var offsetDestinationRoute by chromeScrollState.offsetDestinationRoute
    var offsetDestinationOwnerKey by chromeScrollState.offsetDestinationOwnerKey
    val detailsBottomPanelExpansion by chromeState.detailsBottomPanelExpansion
    val detailsBottomObstruction by chromeState.detailsBottomObstruction
    val detailsBottomPanelRoute by chromeState.detailsBottomPanelRoute
    LaunchedEffect(isSearchOverlayMounted) {
        if (isSearchOverlayMounted) {
            topAppBarState.heightOffset = 0f
            bottomNavOffset = 0f
            chromeScrollState.totalContentScrollOffset.floatValue = 0f
            keepTabsExpandedByScrollDirection = false
        }
    }
    LaunchedEffect(isLandscapeNavigation) {
        if (isLandscapeNavigation) {
            bottomNavOffset = 0f
        }
    }
    LaunchedEffect(topBarHeightPx, topAppBarState) {
        topAppBarState.heightOffsetLimit = -topBarHeightPx.toFloat()
    }
    LaunchedEffect(currentDestinationRoute) {
        if (isDetailsRoute) {
            chromeState.setDetailsBottomPanelExpansion(0f)
            chromeState.setDetailsBottomObstruction(0.dp)
            chromeState.setDetailsBottomPanelRoute(null)
        } else if (!isContentListRoute) {
            chromeState.setDetailsBottomPanelExpansion(0f)
            chromeState.setDetailsBottomObstruction(0.dp)
            chromeState.setDetailsBottomPanelRoute(null)
        }
    }
    LaunchedEffect(shouldShowChrome, navigationSpaceId, isLandscapeNavigation) {
        traceSpaceFab {
            "space changed space=${navigationSpaceId.value} chrome=$shouldShowChrome landscape=$isLandscapeNavigation " +
                "bottomOffset=$bottomNavOffset anchor=$mainSpaceSwitcherFabBounds"
        }
        when {
            isLandscapeNavigation -> {
                canMeasureMainSpaceSwitcherFab = false
                mainSpaceSwitcherFabBounds = null
                mainSpaceSwitcherFabMeasurementSpaceId = navigationSpaceId
            }
            !shouldShowChrome -> canMeasureMainSpaceSwitcherFab = false
            mainSpaceSwitcherFabBounds == null -> {
                mainSpaceSwitcherFabMeasurementSpaceId = navigationSpaceId
                canMeasureMainSpaceSwitcherFab = true
            }
            else -> {
                canMeasureMainSpaceSwitcherFab = false
                delay(MainNavigationMotion.DetailsRouteSlideMillis.toLong())
                mainSpaceSwitcherFabMeasurementSpaceId = navigationSpaceId
                canMeasureMainSpaceSwitcherFab = true
            }
        }
    }
    LaunchedEffect(mainSpaceSwitcherFabCandidate, navigationSpaceId) {
        val candidate = mainSpaceSwitcherFabCandidate ?: return@LaunchedEffect
        if (candidate.first != navigationSpaceId) return@LaunchedEffect
        delay(64L)
        if (mainSpaceSwitcherFabCandidate == candidate && candidate.first == navigationSpaceId) {
            traceSpaceFab {
                "anchor committed space=${navigationSpaceId.value} bounds=${candidate.second}"
            }
            mainSpaceSwitcherFabBounds = candidate.second
        }
    }
    LaunchedEffect(currentTopBarOwnerKey) {
        if (currentTopBarOwnerKey != null) {
            chromeState.setLastChromeTopBarOwnerKey(currentTopBarOwnerKey)
        }
    }
    LaunchedEffect(shouldUseMaterialTopBarScroll, topAppBarState) {
        chromeState.setMaterialTopBarScrollEnabled(shouldUseMaterialTopBarScroll)
        if (!shouldUseMaterialTopBarScroll) {
            topAppBarState.heightOffset = 0f
        }
    }
    LaunchedEffect(navigationSpaceId, currentDestinationRoute, currentTopBarOwnerKey) {
        if (currentDestinationRoute != null && !isImmersiveRoute && !isSearchRoute) {
            if (!isChromeOffsetFromCurrentDestination) {
                topAppBarState.heightOffset = 0f
                bottomNavOffset = 0f
                chromeScrollState.totalContentScrollOffset.floatValue = 0f
                keepTabsExpandedByScrollDirection = false
            }
            offsetDestinationRoute = currentDestinationRoute
            offsetDestinationOwnerKey = currentTopBarOwnerKey
        }
    }
    LaunchedEffect(currentTopLevelKey) {
        val mappedId = currentTopLevelKey?.let(::bottomNavItemIdForTopLevelKey) ?: -1
        if (mappedId != -1) {
            onNavDestinationChanged(mappedId)
        }
    }
    LaunchedEffect(
        navigationSpaceId,
        currentDestinationRoute,
        currentTopBarOwnerKey,
        offsetDestinationRoute,
        offsetDestinationOwnerKey,
        isActiveSpaceRestored,
        topBarHeightPx,
        contentTopInsetPx,
        contentBottomInsetPx,
    ) {
        traceSpaceChrome {
            "state space=${navigationSpaceId.value} nav=${System.identityHashCode(mainNavState)} " +
                "route=$currentDestinationRoute owner=$currentTopBarOwnerKey restored=$isActiveSpaceRestored " +
                "offsetRoute=$offsetDestinationRoute offsetOwner=$offsetDestinationOwnerKey " +
                "topOffset=${topAppBarState.heightOffset} effectiveTop=$effectiveTopBarOffset " +
                "bottomOffset=$bottomNavOffset effectiveBottom=$effectiveBottomNavOffset " +
                "topInset=$contentTopInsetPx bottomInset=$contentBottomInsetPx chrome=$shouldShowChrome"
        }
    }
    LaunchedEffect(contentTopInsetPx, contentBottomInsetPx) {
        onContentInsetsChanged(contentTopInsetPx, contentBottomInsetPx)
    }
    return mainSpaceSwitcherFabBoundsState
}