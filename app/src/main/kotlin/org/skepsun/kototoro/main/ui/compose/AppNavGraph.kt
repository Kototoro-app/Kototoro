package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import org.skepsun.kototoro.home.ui.compose.HomeScreen
import org.skepsun.kototoro.home.ui.compose.HomeScreenActions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.skepsun.kototoro.home.ui.HomeViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import dagger.hilt.android.EntryPointAccessors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.ViewModel
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.compose.KototoroExploreHostRoute
import org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState
import org.skepsun.kototoro.favourites.ui.compose.KototoroFavoritesHostRoute
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityOrganizeScreen
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.core.nav.router
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Rect
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.compose.SearchResultsRoute
import org.skepsun.kototoro.search.ui.compose.SearchRoute
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import org.skepsun.kototoro.core.nav.PendingContentListNavigation
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.RouteLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.resolveSourceTitleForUi
import com.kyant.backdrop.backdrops.layerBackdrop
import org.skepsun.kototoro.details.ui.compose.DetailsScreen
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.compose.handleDetailsAction
import org.skepsun.kototoro.parsers.model.Content
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarTabItem
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.navigation3.MainNavigator
import org.skepsun.kototoro.main.ui.navigation3.MainNavState
import org.skepsun.kototoro.main.ui.navigation3.MainTopLevelNavDisplay
import org.skepsun.kototoro.main.ui.navigation3.NavControllerMainNavigator
import org.skepsun.kototoro.main.ui.navigation3.BookmarksNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey
import org.skepsun.kototoro.main.ui.navigation3.LocalNavKey
import org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey
import org.skepsun.kototoro.main.ui.navigation3.TopLevelNavKey
import org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.remotelist.ui.RemoteListViewModel
import org.skepsun.kototoro.remotelist.ui.ContentListSourceGateViewModel
import org.skepsun.kototoro.search.ui.compose.AppSearchContentListRoute
import org.skepsun.kototoro.main.ui.compose.selectedFirst
import org.skepsun.kototoro.space.ui.LocalBrowseSpaceId
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.spaceViewModelKey

private fun <T> eventCollector(block: suspend (T) -> Unit): FlowCollector<T> = FlowCollector { value ->
    block(value)
}

private data class PendingFavoritesDialog(
    val requestId: Long,
    val isSync: Boolean,
)

private data class FavoritesSelectionDialogState(
    val request: PendingFavoritesDialog,
    val candidates: List<org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel.ImportSource>,
    val selectedIndices: Set<Int>,
)

@Composable
private inline fun <reified VM> spaceBoundHiltViewModel(owner: String): VM
    where VM : ViewModel, VM : SpaceBindableViewModel {
    val spaceId = LocalBrowseSpaceId.current
    val viewModel = hiltViewModel<VM>(key = spaceViewModelKey(owner, spaceId))
    LaunchedEffect(viewModel, spaceId) {
        viewModel.bindSpace(spaceId)
    }
    return viewModel
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isMainRouteTransition(): Boolean {
    return initialState.destination.isMainRoute() && targetState.destination.isMainRoute()
}

private fun NavDestination.isImmersiveRoute(): Boolean {
    return hasRoute<DetailsRoute>() || hasRoute<ContentListRoute>()
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.mainRouteFadeIn(): EnterTransition {
    if (initialState.destination.isImmersiveRoute() && targetState.destination.isMainRoute()) {
        return slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
        ) + fadeIn(tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing))
    }
    return EnterTransition.None
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.mainRouteFadeOut(): ExitTransition {
    if (initialState.destination.isMainRoute() && targetState.destination.isImmersiveRoute()) {
        return slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
        ) + fadeOut(tween(MainNavigationMotion.DetailsExitFadeOutMillis, easing = LinearEasing))
    }
    return ExitTransition.None
}

@Composable
private fun MainRouteScene(
    landscapeStartPadding: androidx.compose.ui.unit.Dp,
    sourceModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = landscapeStartPadding)
            .then(sourceModifier),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

internal interface FavoritesEntityOrganizeResultSource {
    val refreshSignals: kotlinx.coroutines.flow.StateFlow<Boolean>
    val messageSignals: kotlinx.coroutines.flow.StateFlow<String?>

    fun consumeRefresh(): Boolean

    fun consumeMessage(): String?
}

internal class SavedStateHandleFavoritesEntityOrganizeResultSource(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : FavoritesEntityOrganizeResultSource {
    override val refreshSignals: kotlinx.coroutines.flow.StateFlow<Boolean> =
        savedStateHandle.getStateFlow(ENTITY_ORGANIZE_RESULT_REFRESH_KEY, false)

    override val messageSignals: kotlinx.coroutines.flow.StateFlow<String?> =
        savedStateHandle.getStateFlow(ENTITY_ORGANIZE_RESULT_MESSAGE_KEY, null)

    override fun consumeRefresh(): Boolean = consumeEntityOrganizeRefreshResult(savedStateHandle)

    override fun consumeMessage(): String? = consumeEntityOrganizeMessageResult(savedStateHandle)
}

private const val TOP_BAR_OWNER_DISCOVER = "discover"
private const val TOP_BAR_OWNER_HISTORY = "history"
private const val TOP_BAR_OWNER_FAVORITES = "favorites"
private const val TOP_BAR_OWNER_EXPLORE = "explore"
private const val TOP_BAR_OWNER_FEED = "feed"
private const val TOP_BAR_OWNER_LOCAL = "local"
private const val TOP_BAR_OWNER_SUGGESTIONS = "suggestions"
private const val TOP_BAR_OWNER_UPDATED = "updated"
private fun NavDestination.isMainRoute(): Boolean =
    hasRoute<MainShellRoute>()

@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: Any = MainShellRoute,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    bottomBarOffsetPx: Float = 0f,
    bottomBarHeightPx: Int = 0,
    pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper? = null,
    modifier: Modifier = Modifier,
    mainShellChrome: @Composable BoxScope.() -> Unit = {},
    routeFab: @Composable BoxScope.() -> Unit = {},
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit = {},
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit = {},
    onOpenSearch: (SearchNavigationRequest) -> Unit = {},
    onDetailsTransitionRequested: () -> Unit = {},
    onDetailsReturnTransitionRequested: () -> Unit = {},
    onDetailsBottomPanelStateChanged: (Float, Dp) -> Unit = { _, _ -> },
    isLandscapeNavigation: Boolean = false,
    mainNavState: MainNavState? = null,
    suppressNavigationTransitions: Boolean = false,
) {
    val activity = LocalContext.current as FragmentActivity
    val appRouter = activity.router
    val mainActivity = activity as? MainActivity
    val rootView = LocalView.current
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val isMainShellRouteVisible = currentDestination?.hasRoute<MainShellRoute>() == true
    val density = LocalDensity.current
    val landscapeStartPadding = if (isLandscapeNavigation) {
        with(density) { bottomBarHeightPx.toDp() }
    } else {
        0.dp
    }
    val mainNavigator: MainNavigator = remember(navController, mainActivity, mainNavState, onDetailsTransitionRequested) {
        NavControllerMainNavigator(
            navController = navController,
            mainActivity = mainActivity,
            mainNavState = mainNavState,
            onDetailsTransitionRequested = onDetailsTransitionRequested,
        )
    }
    val navigateToDetailsWithContent = remember(mainNavigator) {
        { content: Content, sharedElementKey: String? ->
            mainNavigator.openDetails(content, sharedElementKey)
        }
    }
    val navigateToDetailsWithOrigin = remember(mainNavigator) {
        { origin: org.skepsun.kototoro.details.ui.model.DetailsOrigin, sharedElementKey: String? ->
            mainNavigator.openDetails(origin, sharedElementKey)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { if (suppressNavigationTransitions) EnterTransition.None else mainRouteFadeIn() },
        exitTransition = { if (suppressNavigationTransitions) ExitTransition.None else mainRouteFadeOut() },
        popEnterTransition = { if (suppressNavigationTransitions) EnterTransition.None else mainRouteFadeIn() },
        popExitTransition = { if (suppressNavigationTransitions) ExitTransition.None else mainRouteFadeOut() },
    ) {
        composable<MainShellRoute> { backStackEntry ->
            MainShellRouteContent(
                animatedVisibilityScope = this@composable,
                backStackEntry = backStackEntry,
                navController = navController,
                activity = activity,
                mainActivity = mainActivity,
                appRouter = appRouter,
                rootView = rootView,
                contentPadding = contentPadding,
                landscapeStartPadding = landscapeStartPadding,
                bottomBarOffsetPx = bottomBarOffsetPx,
                bottomBarHeightPx = bottomBarHeightPx,
                pageSaveHelper = pageSaveHelper,
                isLandscapeNavigation = isLandscapeNavigation,
                mainNavigator = mainNavigator,
                mainNavState = checkNotNull(mainNavState) {
                    "MainShellRoute requires MainNavState"
                },
                isRouteVisible = isMainShellRouteVisible,
                onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
                onContextualMenuActionsChanged = onContextualMenuActionsChanged,
                onOpenSearch = onOpenSearch,
                navigateToDetailsWithContent = navigateToDetailsWithContent,
                navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
                mainShellChrome = mainShellChrome,
                routeFab = routeFab,
            )
        }
        composable<EntityOrganizeRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EntityOrganizeRoute>()
            val initialSelectedIds = remember(route.selectedContentIds) {
                parseEntityOrganizeSelection(route.selectedContentIds)
            }
            EntityOrganizeScreen(
                initialSelectedContentIds = initialSelectedIds,
                onBack = { shouldRefreshFavorites, message ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        ENTITY_ORGANIZE_RESULT_REFRESH_KEY,
                        shouldRefreshFavorites,
                    )
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        ENTITY_ORGANIZE_RESULT_MESSAGE_KEY,
                        message,
                    )
                    navController.navigateUp()
                },
            )
        }
        composable<SearchRoute> { backStackEntry ->
            val viewModel = hiltViewModel<org.skepsun.kototoro.search.ui.multi.SearchViewModel>()
            BackHandler {
                mainNavigator.pop()
            }
            RouteLiquidGlassBackdrop(
                ownerKey = backStackEntry.id,
                active = currentBackStackEntry?.id == backStackEntry.id,
            ) { routeBackdrop ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
                                    routeBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        SearchResultsRoute(
                            viewModel = viewModel,
                            onBackClick = { mainNavigator.pop() },
                            onOpenContent = { content, sharedElementKey ->
                                navigateToDetailsWithContent(content, sharedElementKey)
                            },
                            onPickContent = { },
                            onOpenSourceResults = { item ->
                                if (item.listFilter == null) {
                                    mainNavigator.openContentList(
                                        source = item.source,
                                        filter = ContentListFilter(query = viewModel.query),
                                        sortOrder = null,
                                    )
                                } else {
                                    mainNavigator.openContentList(item.source, item.listFilter, item.sortOrder)
                                }
                            },
                            onManageLanguagePresets = appRouter::openSourcePresets,
                            onOpenGlobalTagBlacklist = appRouter::openGlobalTagBlacklist,
                            onSubmitSearch = { query, kind, sourceTypes, contentKinds, advancedQuery, pinnedOnly, hideEmpty ->
                                onOpenSearch(
                                    SearchNavigationRequest(
                                        query = query,
                                        kind = kind,
                                        sourceTypes = sourceTypes,
                                        contentKinds = contentKinds,
                                        advancedQuery = advancedQuery,
                                        pinnedOnly = pinnedOnly,
                                        hideEmpty = hideEmpty,
                                        requestId = System.nanoTime(),
                                    ),
                                )
                            },
                            onShareSelection = { items ->
                                ShareHelper(activity).shareContentLinks(items)
                            },
                            onSaveSelection = { items ->
                                appRouter.showDownloadDialog(items, rootView)
                            },
                            onFavouriteSelection = { items ->
                                appRouter.showFavoriteDialog(items)
                            },
                            isPickMode = false,
                        )
                    }
                    routeFab()
                }
            }
        }

        composable<ContentListRoute>(
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeIn(tween(MainNavigationMotion.DetailsEnterFadeInMillis, easing = LinearEasing))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeOut(tween(MainNavigationMotion.DetailsExitFadeOutMillis, easing = LinearEasing))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeIn(tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeOut(tween(MainNavigationMotion.DetailsPopExitFadeOutMillis, easing = LinearEasing))
            },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<ContentListRoute>()
            val pendingFilter = remember(route.sourceName) { PendingContentListNavigation.consumeFilter() }
            val pendingSortOrder = remember(route.sourceName) { PendingContentListNavigation.consumeSortOrder() }
            val sourceGateViewModel = hiltViewModel<ContentListSourceGateViewModel>()
            val isSourceResolutionReady by sourceGateViewModel.isResolutionReady.collectAsStateWithLifecycle()
            BackHandler {
                mainNavigator.pop()
            }
            RouteLiquidGlassBackdrop(
                ownerKey = backStackEntry.id,
                active = currentBackStackEntry?.id == backStackEntry.id,
            ) { routeBackdrop ->
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isSourceResolutionReady) {
                    val viewModel = hiltViewModel<RemoteListViewModel>()
                    LaunchedEffect(viewModel, pendingFilter, pendingSortOrder) {
                        pendingSortOrder?.let(viewModel.filterCoordinator::setSortOrder)
                        pendingFilter?.let(viewModel.filterCoordinator::setAdjusted)
                    }
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                        AppSearchContentListRoute(
                            appRouter = appRouter,
                            onBackClick = { mainNavigator.pop() },
                            activeSpaceId = null,
                            onSpaceSwitcherClick = {},
                            onOpenDetails = { content, sharedElementKey ->
                                navigateToDetailsWithContent(content, sharedElementKey)
                            },
                            viewModel = viewModel,
                        )
                    }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
                                        routeBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            KototoroLoadingIndicator()
                        }
                    }
                    routeFab()
                }
            }
        }

        composable<DetailsRoute>(
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeIn(tween(MainNavigationMotion.DetailsEnterFadeInMillis, easing = LinearEasing))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeOut(tween(MainNavigationMotion.DetailsExitFadeOutMillis, easing = LinearEasing))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeIn(tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeOut(tween(MainNavigationMotion.DetailsPopExitFadeOutMillis, easing = LinearEasing))
            },
        ) { backStackEntry ->
            val detailsViewModel = hiltViewModel<DetailsViewModel>()
            val pagesViewModel = hiltViewModel<org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel>()
            val bookmarksViewModel = hiltViewModel<org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel>()
            val detailsCoroutineScope = rememberCoroutineScope()

            val entryPoint = remember(activity) {
                dagger.hilt.android.EntryPointAccessors.fromActivity(
                    activity,
                    DetailsRouteEntryPoint::class.java,
                )
            }
            val effectivePageSaveHelper = pageSaveHelper ?: remember(activity) {
                entryPoint.pageSaveHelperFactory().create(activity)
            }
            val overrideEditLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    detailsViewModel.reload()
                }
            }

            RouteLiquidGlassBackdrop(
                ownerKey = backStackEntry.id,
                active = currentBackStackEntry?.id == backStackEntry.id,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                    val pendingContent = remember { PendingDetailsNavigation.lastContent() }
                    val pendingSharedKey = remember { PendingDetailsNavigation.lastSharedElementKey() }
                    val mangaDetails by detailsViewModel.mangaDetails.collectAsStateWithLifecycle()
                    val sharedKey = remember(pendingSharedKey, mangaDetails, pendingContent) {
                        pendingSharedKey ?: run {
                            val content = mangaDetails?.toContent() ?: pendingContent
                            content?.let { c ->
                                contentCoverSharedKey(c.source.name, c.coverUrl.orEmpty())
                            }
                        }
                    }
                    BackHandler {
                        mainNavigator.pop()
                    }
                    DetailsScreen(
                        viewModel = detailsViewModel,
                        pagesViewModel = pagesViewModel,
                        bookmarksViewModel = bookmarksViewModel,
                        settings = entryPoint.settings(),
                        appRouter = appRouter,
                        pageSaveHelper = effectivePageSaveHelper,
                        onBackClick = {
                            mainNavigator.pop()
                        },
                        activeSpaceId = null,
                        onSpaceSwitcherClick = {},
                        onBottomPanelStateChanged = onDetailsBottomPanelStateChanged,
                        sharedElementKey = sharedKey,
                        onActionClick = { action ->
                            handleDetailsAction(
                                action = action,
                                appRouter = appRouter,
                                viewModel = detailsViewModel,
                                appShortcutManager = entryPoint.appShortcutManager(),
                                coroutineScope = detailsCoroutineScope,
                                snackbarHost = rootView,
                                overrideEditLauncher = overrideEditLauncher,
                                onOpenSourceList = { source, filter, sortOrder ->
                                    mainNavigator.openContentList(source, filter, sortOrder)
                                },
                                onFinish = { mainNavigator.pop() },
                            )
                        },
                    )
                    }
                    routeFab()
                }
            }
        }
    }
}

@Composable
internal fun MainShellRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    backStackEntry: NavBackStackEntry,
    navController: NavHostController,
    activity: FragmentActivity,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    rootView: android.view.View,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    landscapeStartPadding: androidx.compose.ui.unit.Dp,
    bottomBarOffsetPx: Float,
    bottomBarHeightPx: Int,
    pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper?,
    isLandscapeNavigation: Boolean,
    mainNavigator: MainNavigator,
    mainNavState: MainNavState,
    isRouteVisible: Boolean,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit,
    onOpenSearch: (SearchNavigationRequest) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    navigateToDetailsWithOrigin: (org.skepsun.kototoro.details.ui.model.DetailsOrigin, String?) -> Unit,
    routeFab: @Composable BoxScope.() -> Unit,
    mainShellChrome: @Composable BoxScope.() -> Unit,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val entityOrganizeResultSource = remember(backStackEntry.savedStateHandle) {
        SavedStateHandleFavoritesEntityOrganizeResultSource(backStackEntry.savedStateHandle)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        val useLiquidGlass = LocalInterfaceStyle.current == InterfaceStyle.IOS
        RouteLiquidGlassBackdrop(
            ownerKey = backStackEntry.id,
            active = isRouteVisible,
        ) { layerBackdrop ->
            Box(modifier = Modifier.fillMaxSize()) {
                MainRouteScene(
                    landscapeStartPadding = landscapeStartPadding,
                    sourceModifier = Modifier
                        .then(
                            if (useLiquidGlass && layerBackdrop != null) {
                                Modifier.layerBackdrop(layerBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    MainTopLevelNavDisplay(
                        navState = mainNavState,
                        modifier = Modifier.fillMaxSize(),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScopeOverride = animatedVisibilityScope,
                    ) { key ->
                        CompositionLocalProvider(
                            LocalLiquidGlassBackdrop provides null,
                            LocalLiquidGlassLayerBackdrop provides null,
                        ) {
                            MainShellTopLevelEntryContent(
                            key = key,
                            navController = navController,
                            activity = activity,
                            mainActivity = mainActivity,
                            appRouter = appRouter,
                            rootView = rootView,
                            contentPadding = contentPadding,
                            bottomBarOffsetPx = bottomBarOffsetPx,
                            bottomBarHeightPx = bottomBarHeightPx,
                            pageSaveHelper = pageSaveHelper,
                            isLandscapeNavigation = isLandscapeNavigation,
                            mainNavigator = mainNavigator,
                            isRouteVisible = isRouteVisible &&
                                mainNavState.selectedTopLevel == org.skepsun.kototoro.main.ui.navigation3.HomeNavKey,
                            entityOrganizeResultSource = entityOrganizeResultSource,
                            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
                            onContextualMenuActionsChanged = onContextualMenuActionsChanged,
                            onOpenSearch = onOpenSearch,
                            navigateToDetailsWithContent = navigateToDetailsWithContent,
                            navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
                            )
                        }
                    }
                }
                mainShellChrome()
                CompositionLocalProvider(
                    LocalLiquidGlassBackdrop provides layerBackdrop,
                    LocalLiquidGlassLayerBackdrop provides layerBackdrop,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        routeFab()
                    }
                }
            }
        }
    }
}

@Composable
private fun MainShellTopLevelEntryContent(
    key: TopLevelNavKey,
    navController: NavHostController,
    activity: FragmentActivity,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    rootView: android.view.View,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    bottomBarOffsetPx: Float,
    bottomBarHeightPx: Int,
    pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper?,
    isLandscapeNavigation: Boolean,
    mainNavigator: MainNavigator,
    isRouteVisible: Boolean,
    entityOrganizeResultSource: FavoritesEntityOrganizeResultSource,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit,
    onOpenSearch: (SearchNavigationRequest) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    navigateToDetailsWithOrigin: (org.skepsun.kototoro.details.ui.model.DetailsOrigin, String?) -> Unit,
) {
    val animatedVisibilityScope = checkNotNull(LocalNavAnimatedVisibilityScope.current) {
        "MainShellTopLevelEntryContent requires LocalNavAnimatedVisibilityScope"
    }
    when (key) {
        org.skepsun.kototoro.main.ui.navigation3.HomeNavKey -> HomeTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            navController = navController,
            activity = activity,
            mainActivity = mainActivity,
            appRouter = appRouter,
            rootView = rootView,
            contentPadding = contentPadding,
            mainNavigator = mainNavigator,
            onOpenSearch = onOpenSearch,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
            isRouteVisible = isRouteVisible,
        )
        org.skepsun.kototoro.main.ui.navigation3.DiscoverNavKey -> BrowseTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            mainActivity = mainActivity,
            appRouter = appRouter,
            contentPadding = contentPadding,
            mainNavigator = mainNavigator,
            ownerRoute = TOP_BAR_OWNER_DISCOVER,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            onContextualMenuActionsChanged = onContextualMenuActionsChanged,
            navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
        )
        org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey -> HistoryTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            activity = activity,
            mainActivity = mainActivity,
            appRouter = appRouter,
            rootView = rootView,
            contentPadding = contentPadding,
            bottomBarOffsetPx = bottomBarOffsetPx,
            bottomBarHeightPx = bottomBarHeightPx,
            isLandscapeNavigation = isLandscapeNavigation,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            onContextualMenuActionsChanged = onContextualMenuActionsChanged,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
            navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
        )
        org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey -> FavoritesTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            entityOrganizeResultSource = entityOrganizeResultSource,
            mainActivity = mainActivity,
            appRouter = appRouter,
            contentPadding = contentPadding,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            onContextualMenuActionsChanged = onContextualMenuActionsChanged,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
            navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
        )
        org.skepsun.kototoro.main.ui.navigation3.ExploreNavKey -> BrowseTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            mainActivity = mainActivity,
            appRouter = appRouter,
            contentPadding = contentPadding,
            mainNavigator = mainNavigator,
            ownerRoute = TOP_BAR_OWNER_EXPLORE,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            onContextualMenuActionsChanged = onContextualMenuActionsChanged,
            navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
        )
        org.skepsun.kototoro.main.ui.navigation3.FeedNavKey -> FeedTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            mainActivity = mainActivity,
            appRouter = appRouter,
            contentPadding = contentPadding,
            mainNavigator = mainNavigator,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
            navigateToDetailsWithOrigin = navigateToDetailsWithOrigin,
        )
        org.skepsun.kototoro.main.ui.navigation3.LocalNavKey -> LocalTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            appRouter = appRouter,
            contentPadding = contentPadding,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            onContextualMenuActionsChanged = onContextualMenuActionsChanged,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
        )
        org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey -> SuggestionsTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            appRouter = appRouter,
            contentPadding = contentPadding,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
        )
        org.skepsun.kototoro.main.ui.navigation3.BookmarksNavKey -> BookmarksTopLevelRouteContent(
            mainActivity = mainActivity,
            appRouter = appRouter,
            contentPadding = contentPadding,
            pageSaveHelper = requireNotNull(pageSaveHelper) {
                "BookmarksRoute requires a pre-registered PageSaveHelper"
            },
        )
        org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey -> UpdatedTopLevelRouteContent(
            animatedVisibilityScope = animatedVisibilityScope,
            activity = activity,
            appRouter = appRouter,
            contentPadding = contentPadding,
            onExploreSourceSelectionTopBarChanged = onExploreSourceSelectionTopBarChanged,
            navigateToDetailsWithContent = navigateToDetailsWithContent,
        )
    }
}

@Composable
internal fun HomeTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    navController: NavHostController,
    activity: FragmentActivity,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    rootView: android.view.View,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    mainNavigator: MainNavigator,
    onOpenSearch: (SearchNavigationRequest) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    isRouteVisible: Boolean = true,
) {
    val viewModel = spaceBoundHiltViewModel<HomeViewModel>("home")
    val state by viewModel.summaryState.collectAsStateWithLifecycle()
    val isRandomLoading by viewModel.isRandomLoading.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel.onOpenContent, navigateToDetailsWithContent) {
        viewModel.onOpenContent.collect { event ->
            event?.consume { contentEvent ->
                navigateToDetailsWithContent(contentEvent.content, null)
            }
        }
    }

    LaunchedEffect(viewModel.onActionDone) {
        val observer = org.skepsun.kototoro.core.ui.util.ReversibleActionObserver(rootView)
        viewModel.onActionDone.collect { event ->
            event?.consume(observer)
        }
    }

    LaunchedEffect(viewModel.onError, activity) {
        val host = activity.window.decorView.rootView
        val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseComposeActivity)?.exceptionResolver
        val observer = org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver(host, null, resolver, null)
        viewModel.onError.collect { event ->
            event?.consume(observer)
        }
    }

    DisposableEffect(mainActivity, viewModel, state.selectedTab, state.selectedSourceTags) {
        val callback = object : SearchBarFilterViewController.Callback {
            override fun getSelectedContentType(): BrowseGroupTab = when (state.selectedTab) {
                org.skepsun.kototoro.home.ui.HomeContentTab.MANGA -> BrowseGroupTab.Content
                org.skepsun.kototoro.home.ui.HomeContentTab.NOVEL -> BrowseGroupTab.Novel
                org.skepsun.kototoro.home.ui.HomeContentTab.VIDEO -> BrowseGroupTab.Video
                null -> BrowseGroupTab.All
            }

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                viewModel.setSelectedTab(
                    when (if (getSelectedContentType() == tab) BrowseGroupTab.All else tab) {
                        BrowseGroupTab.Content -> org.skepsun.kototoro.home.ui.HomeContentTab.MANGA
                        BrowseGroupTab.Novel -> org.skepsun.kototoro.home.ui.HomeContentTab.NOVEL
                        BrowseGroupTab.Video -> org.skepsun.kototoro.home.ui.HomeContentTab.VIDEO
                        else -> null
                    },
                )
            }

            override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                state.selectedSourceTags

            override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                val current = state.selectedSourceTags
                viewModel.setSelectedSourceTags(
                    when {
                        tag == null -> emptySet()
                        tag in current -> current - tag
                        else -> current + tag
                    },
                )
            }
        }
        mainActivity?.setActiveFilterCallback(callback)
        onDispose {
            mainActivity?.clearActiveFilterCallback(callback)
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        val onHomeContentClick = remember(navigateToDetailsWithContent) {
            { content: Content, _: Rect?, sharedElementKey: String? ->
                navigateToDetailsWithContent(content, sharedElementKey)
            }
        }
        val onHomeSettingsClick = remember(appRouter) { { appRouter.openSettings() } }
        val onHomeReaderSettingsClick = remember(appRouter) { { appRouter.openReaderSettings() } }
        val onHomeViewAllRecentClick = remember(mainNavigator) {
            {
                mainNavigator.openTopLevel(HistoryNavKey)
            }
        }
        val onHomeViewAllUpdatesClick = remember(mainNavigator) {
            {
                mainNavigator.openTopLevel(UpdatedNavKey)
            }
        }
        val onHomeViewAllRecommendationsClick = remember(mainNavigator) {
            {
                mainNavigator.openTopLevel(SuggestionsNavKey)
            }
        }
        val onHomeRecentSearchClick = remember(onOpenSearch) {
            { query: String ->
                onOpenSearch(
                    SearchNavigationRequest(
                        query = query,
                        kind = org.skepsun.kototoro.search.domain.SearchKind.SIMPLE,
                        sourceTypes = org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES,
                        contentKinds = org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS,
                        advancedQuery = null,
                        pinnedOnly = false,
                        hideEmpty = false,
                        requestId = System.nanoTime(),
                    ),
                )
            }
	        }
	        val onHomeSetupWizardClick = remember(appRouter) { { appRouter.showWelcomeSheet() } }
	        val onHomeManageSourcesClick = remember(appRouter) { { appRouter.openManageSources() } }
        val onHomeLibraryOpenClick = remember(mainNavigator) {
            {
                mainNavigator.openTopLevel(FavoritesNavKey)
            }
        }
        val onHomeBookmarksClick = remember(mainNavigator) {
            {
                mainNavigator.openTopLevel(BookmarksNavKey)
            }
        }
        val onHomeLocalClick = remember(mainNavigator) {
            {
                mainNavigator.openTopLevel(LocalNavKey)
            }
        }
        val onHomeDownloadsClick = remember(appRouter) { { appRouter.openDownloads() } }
        val onHomeRandomClick = remember(viewModel) { { viewModel.openRandom() } }
        val onHomeAutoTranslateClick = remember(appRouter) { { appRouter.openTranslationSettings() } }
        val homeActions = remember(
            onHomeSettingsClick,
            onHomeReaderSettingsClick,
            onHomeViewAllRecentClick,
            onHomeViewAllUpdatesClick,
	            onHomeViewAllRecommendationsClick,
	            onHomeRecentSearchClick,
	            onHomeSetupWizardClick,
	            onHomeManageSourcesClick,
            onHomeLibraryOpenClick,
            onHomeBookmarksClick,
            onHomeLocalClick,
            onHomeDownloadsClick,
            onHomeRandomClick,
            onHomeAutoTranslateClick,
        ) {
            HomeScreenActions(
                onSettingsClick = onHomeSettingsClick,
                onReaderSettingsClick = onHomeReaderSettingsClick,
                onViewAllRecentClick = onHomeViewAllRecentClick,
	                onViewAllUpdatesClick = onHomeViewAllUpdatesClick,
	                onViewAllRecommendationsClick = onHomeViewAllRecommendationsClick,
	                onRecentSearchClick = onHomeRecentSearchClick,
	                onSetupWizardClick = onHomeSetupWizardClick,
	                onManageSourcesClick = onHomeManageSourcesClick,
                onLibraryOpenClick = onHomeLibraryOpenClick,
                onBookmarksClick = onHomeBookmarksClick,
                onLocalClick = onHomeLocalClick,
                onDownloadsClick = onHomeDownloadsClick,
                onRandomClick = onHomeRandomClick,
                onAutoTranslateClick = onHomeAutoTranslateClick,
            )
        }
        HomeScreen(
            contentPadding = contentPadding,
            state = state,
            onContentClick = onHomeContentClick,
            actions = homeActions,
            isRandomLoading = isRandomLoading,
        )
    }
}

@Composable
internal fun BrowseTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    mainNavigator: MainNavigator,
    ownerRoute: String,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit,
    navigateToDetailsWithOrigin: (org.skepsun.kototoro.details.ui.model.DetailsOrigin, String?) -> Unit,
) {
    val exploreViewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.explore.ui.ExploreViewModel>("explore")
    val discoverViewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.discover.ui.DiscoverViewModel>("discover")
    val selectedGroupTab by exploreViewModel.currentGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by exploreViewModel.currentSourceTags.collectAsStateWithLifecycle()
    val isEmptySourcesHidden by exploreViewModel.isEmptySourcesHidden.collectAsStateWithLifecycle()

    DisposableEffect(ownerRoute, exploreViewModel, isEmptySourcesHidden) {
        onContextualMenuActionsChanged(
            RouteScopedTopBarMenuActions(
                ownerRoute,
                listOf(
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.manage_sources,
                        org.skepsun.kototoro.R.drawable.ic_manga_source,
                    ) {
                        appRouter.openManageSources()
                    },
                    KototoroTopBarMenuAction(
                        if (isEmptySourcesHidden) {
                            org.skepsun.kototoro.R.string.show_empty_sources
                        } else {
                            org.skepsun.kototoro.R.string.hide_empty_sources
                        },
                        if (isEmptySourcesHidden) {
                            org.skepsun.kototoro.R.drawable.ic_eye
                        } else {
                            org.skepsun.kototoro.R.drawable.ic_eye_off
                        },
                    ) {
                        exploreViewModel.setEmptySourcesHidden(!isEmptySourcesHidden)
                    },
                ),
            ),
        )
        onDispose {
            onContextualMenuActionsChanged(RouteScopedTopBarMenuActions(ownerRoute, emptyList()))
        }
    }

    DisposableEffect(mainActivity, exploreViewModel, selectedGroupTab, selectedSourceTags) {
        val callback = object : SearchBarFilterViewController.Callback {
            override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                exploreViewModel.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
            }

            override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                selectedSourceTags

            override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                exploreViewModel.setSelectedSourceTags(
                    when {
                        tag == null -> emptySet()
                        tag in selectedSourceTags -> selectedSourceTags - tag
                        else -> selectedSourceTags + tag
                    },
                )
            }
        }
        mainActivity?.setActiveFilterCallback(callback)
        onDispose {
            mainActivity?.clearActiveFilterCallback(callback)
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        KototoroExploreHostRoute(
            appRouter = appRouter,
            contentPadding = contentPadding,
            exploreViewModel = exploreViewModel,
            discoverViewModel = discoverViewModel,
            onSourceSelectionTopBarChanged = {
                onExploreSourceSelectionTopBarChanged(
                    RouteScopedTopBarOverrideState(ownerRoute, it),
                )
            },
            onNavigateToDetails = navigateToDetailsWithOrigin,
            onOpenSourceList = { source ->
                mainNavigator.openContentList(source, null, null)
            },
        )
    }
}

@Composable
internal fun FeedTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    mainNavigator: MainNavigator,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    navigateToDetailsWithOrigin: (org.skepsun.kototoro.details.ui.model.DetailsOrigin, String?) -> Unit,
) {
    val viewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.tracker.ui.feed.FeedViewModel>("feed")
    val items by viewModel.content.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.currentCategoryId.collectAsStateWithLifecycle()
    val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(mainActivity, viewModel, selectedGroupTab, selectedSourceTags) {
        val callback = object : SearchBarFilterViewController.Callback {
            override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                viewModel.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
            }

            override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                selectedSourceTags

            override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                viewModel.setSelectedSourceTags(
                    when {
                        tag == null -> emptySet()
                        tag in selectedSourceTags -> selectedSourceTags - tag
                        else -> selectedSourceTags + tag
                    },
                )
            }
        }
        mainActivity?.setActiveFilterCallback(callback)
        onDispose {
            mainActivity?.clearActiveFilterCallback(callback)
        }
    }

    DisposableEffect(viewModel, activity, lifecycleOwner) {
        val menuProvider = org.skepsun.kototoro.tracker.ui.feed.FeedMenuProvider(
            snackbarHost = activity?.window?.decorView?.rootView ?: android.view.View(activity),
            viewModel = viewModel,
        )
        activity?.addMenuProvider(menuProvider, lifecycleOwner, androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose {
            activity?.removeMenuProvider(menuProvider)
        }
    }

    LaunchedEffect(viewModel.onError, activity) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseComposeActivity)?.exceptionResolver
        val observer = org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver(host, null, resolver) {
            resolved -> if (resolved) viewModel.update()
        }
        viewModel.onError.collect { event: org.skepsun.kototoro.core.util.Event<Throwable>? ->
            event?.consume(observer)
        }
    }

    SideEffect {
        onExploreSourceSelectionTopBarChanged(
            RouteScopedTopBarOverrideState(
                TOP_BAR_OWNER_FEED,
                null,
            ),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onExploreSourceSelectionTopBarChanged(RouteScopedTopBarOverrideState(TOP_BAR_OWNER_FEED, null))
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        org.skepsun.kototoro.tracker.ui.feed.compose.FeedScreen(
            contentPadding = contentPadding,
            items = items,
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.update() },
            onLoadMore = { viewModel.requestMoreItems() },
            onFeedItemClick = { item, _ ->
                viewModel.onItemClick(item)
                val content = item.toContentWithOverride()
                val sharedElementKey = contentCoverSharedKey(
                    item.manga.source.name,
                    item.imageUrl.orEmpty(),
                    instanceKey = "feed_${item.id}",
                )
                if (item.entityId != null) {
                    navigateToDetailsWithOrigin(
                        org.skepsun.kototoro.details.ui.model.DetailsOrigin.EntityGraph(
                            entityId = item.entityId,
                            preferredLocalMangaId = item.preferredLocalMangaId ?: content.id,
                            initialProjectionLocalMangaId = content.id,
                        ),
                        sharedElementKey,
                    )
                } else {
                    navigateToDetailsWithContent(content, sharedElementKey)
                }
            },
            onUpdatedContentItemClick = { contentItem, _ ->
                val content = contentItem.model.toContentWithOverride()
                val sharedElementKey = contentCoverSharedKey(
                    contentItem.model.manga.source.name,
                    contentItem.model.coverUrl.orEmpty(),
                    instanceKey = "feed_updated_${contentItem.groupKey}",
                )
                when {
                    contentItem.entityId != null -> navigateToDetailsWithOrigin(
                        org.skepsun.kototoro.details.ui.model.DetailsOrigin.EntityGraph(
                            entityId = contentItem.entityId,
                            preferredLocalMangaId = contentItem.preferredLocalMangaId ?: content.id,
                            initialProjectionLocalMangaId = content.id,
                        ),
                        sharedElementKey,
                    )
                    else -> navigateToDetailsWithContent(content, sharedElementKey)
                }
            },
            onUpdatedContentMoreClick = {
                mainNavigator.openTopLevel(UpdatedNavKey)
            },
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            onCategorySelected = viewModel::selectCategory,
            onQuickFilterOptionClick = viewModel::toggleFilterOption,
            showCategoryFilterInline = true,
        )
    }
}

@Composable
internal fun LocalTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
) {
    val viewModel = hiltViewModel<org.skepsun.kototoro.local.ui.LocalListViewModel>()
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    var pendingRemoveSelection by remember { mutableStateOf<Set<Long>?>(null) }

    DisposableEffect(appRouter) {
        onContextualMenuActionsChanged(
            RouteScopedTopBarMenuActions(
                ownerRoute = TOP_BAR_OWNER_LOCAL,
                actions = buildList {
                    add(
                        KototoroTopBarMenuAction(
                            org.skepsun.kototoro.R.string._import,
                            org.skepsun.kototoro.R.drawable.ic_import,
                        ) {
                            appRouter.showImportDialog()
                        },
                    )
                    if (appRouter.isFilterSupported()) {
                        add(
                            KototoroTopBarMenuAction(
                                org.skepsun.kototoro.R.string.filter,
                                org.skepsun.kototoro.R.drawable.ic_filter_menu,
                            ) {
                                appRouter.showFilterSheet()
                            },
                        )
                    }
                    add(
                        KototoroTopBarMenuAction(
                            org.skepsun.kototoro.R.string.directories,
                            org.skepsun.kototoro.R.drawable.ic_folder_file,
                        ) {
                            appRouter.openDirectoriesSettings()
                        },
                    )
                },
            ),
        )
        onDispose {
            onContextualMenuActionsChanged(
                RouteScopedTopBarMenuActions(
                    ownerRoute = TOP_BAR_OWNER_LOCAL,
                    actions = emptyList(),
                ),
            )
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        org.skepsun.kototoro.list.ui.compose.AppContentListRoute(
            viewModel = viewModel,
            contentPadding = contentPadding,
            appRouter = appRouter,
            pullRefreshEnabled = false,
            onTopBarOverrideChanged = {
                onExploreSourceSelectionTopBarChanged(
                    RouteScopedTopBarOverrideState(
                        TOP_BAR_OWNER_LOCAL,
                        LayeredTopBarOverrideState(contextualOverrideState = it),
                    ),
                )
            },
            showRemoveOption = true,
            sharedElementInstanceKey = "main_local",
            isContentTypeFilterVisible = true,
            onNavigateToDetails = { _, content, sharedKey ->
                navigateToDetailsWithContent(content, sharedKey)
            },
            isSourceTagFilterVisible = false,
            onRemoveSelection = { ids ->
                pendingRemoveSelection = ids.toSet()
            },
            onShareSelection = { ids ->
                if (activity != null) {
                    val files = viewModel.content.value
                        .filter {
                            it is org.skepsun.kototoro.list.ui.model.ContentListModel && it.id in ids
                        }
                        .mapNotNull {
                            (it as? org.skepsun.kototoro.list.ui.model.ContentListModel)
                                ?.manga
                                ?.url
                                ?.let { url -> java.io.File(android.net.Uri.parse(url).path ?: "") }
                        }
                    org.skepsun.kototoro.core.util.ShareHelper(activity).shareCbz(files)
                }
            },
            onEmptyActionClick = { appRouter.showImportDialog() },
            listHeader = null,
        )

        pendingRemoveSelection?.let { ids ->
            AlertDialog(
                onDismissRequest = { pendingRemoveSelection = null },
                title = {
                    Text(text = stringResource(org.skepsun.kototoro.R.string.delete_manga))
                },
                text = {
                    Text(text = stringResource(org.skepsun.kototoro.R.string.text_delete_local_manga_batch))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRemoveSelection = null
                            viewModel.delete(ids)
                        },
                    ) {
                        Text(text = stringResource(org.skepsun.kototoro.R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoveSelection = null }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
internal fun SuggestionsTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
) {
    val viewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.suggestions.ui.SuggestionsViewModel>("suggestions")
    var suggestionsContextualTopBarOverride by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var suggestionsFilterRailOverride by remember { mutableStateOf<CompactFilterRailOverrideState?>(null) }

    SideEffect {
        onExploreSourceSelectionTopBarChanged(
            RouteScopedTopBarOverrideState(
                TOP_BAR_OWNER_SUGGESTIONS,
                LayeredTopBarOverrideState(
                    filterRailState = suggestionsFilterRailOverride,
                    contextualOverrideState = suggestionsContextualTopBarOverride,
                ),
            ),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onExploreSourceSelectionTopBarChanged(RouteScopedTopBarOverrideState(TOP_BAR_OWNER_SUGGESTIONS, null))
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        org.skepsun.kototoro.list.ui.compose.AppContentListRoute(
            viewModel = viewModel,
            contentPadding = contentPadding,
            appRouter = appRouter,
            onTopBarOverrideChanged = { suggestionsContextualTopBarOverride = it },
            showRemoveOption = false,
            sharedElementInstanceKey = "main_suggestions",
            isContentTypeFilterVisible = false,
            isSourceTagFilterVisible = false,
            onNavigateToDetails = { _, content, sharedKey ->
                navigateToDetailsWithContent(content, sharedKey)
            },
            emitFilterRailOverride = false,
            onAddMenuProvider = { act, _, _ ->
                object : androidx.core.view.MenuProvider {
                    override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
                        menuInflater.inflate(org.skepsun.kototoro.R.menu.opt_suggestions, menu)
                        menuInflater.inflate(org.skepsun.kototoro.R.menu.opt_list, menu)
                    }

                    override fun onPrepareMenu(menu: android.view.Menu) {
                        menu.findItem(org.skepsun.kototoro.R.id.action_settings_suggestions)?.isVisible = true
                    }

                    override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean = when (menuItem.itemId) {
                        org.skepsun.kototoro.R.id.action_update -> {
                            viewModel.updateSuggestions()
                            com.google.android.material.snackbar.Snackbar.make(
                                act.window.decorView.rootView,
                                org.skepsun.kototoro.R.string.suggestions_updating,
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
                            ).show()
                            true
                        }
                        org.skepsun.kototoro.R.id.action_list_mode -> {
                            appRouter.showListConfigSheet(org.skepsun.kototoro.list.ui.config.ListConfigSection.Suggestions)
                            true
                        }
                        org.skepsun.kototoro.R.id.action_settings_suggestions -> {
                            appRouter.openSuggestionsSettings()
                            true
                        }
                        else -> false
                    }
                }
            },
        )
    }
}

@Composable
internal fun BookmarksTopLevelRouteContent(
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper,
) {
    val viewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.bookmarks.ui.AllBookmarksViewModel>("bookmarks")
    val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()

    DisposableEffect(mainActivity, viewModel, selectedGroupTab, selectedSourceTags) {
        val callback = object : SearchBarFilterViewController.Callback {
            override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                viewModel.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
            }

            override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                selectedSourceTags

            override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                viewModel.setSelectedSourceTags(
                    when {
                        tag == null -> emptySet()
                        tag in selectedSourceTags -> selectedSourceTags - tag
                        else -> selectedSourceTags + tag
                    },
                )
            }
        }
        mainActivity?.setActiveFilterCallback(callback)
        onDispose {
            mainActivity?.clearActiveFilterCallback(callback)
        }
    }

    org.skepsun.kototoro.bookmarks.ui.compose.AppBookmarksRoute(
        viewModel = viewModel,
        contentPadding = contentPadding,
        appRouter = appRouter,
        pageSaveHelper = pageSaveHelper,
    )
}

@Composable
internal fun UpdatedTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    activity: FragmentActivity,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
) {
    val viewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.tracker.ui.updates.UpdatesViewModel>("updated")
    val headerQuickFilter by viewModel.headerQuickFilter.collectAsStateWithLifecycle()
    var updatedContextualTopBarOverride by remember { mutableStateOf<TopBarOverrideState?>(null) }

    SideEffect {
        onExploreSourceSelectionTopBarChanged(
            RouteScopedTopBarOverrideState(
                TOP_BAR_OWNER_UPDATED,
                updatedContextualTopBarOverride,
            ),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onExploreSourceSelectionTopBarChanged(RouteScopedTopBarOverrideState(TOP_BAR_OWNER_UPDATED, null))
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        org.skepsun.kototoro.list.ui.compose.AppContentListRoute(
            viewModel = viewModel,
            contentPadding = contentPadding,
            appRouter = appRouter,
            onTopBarOverrideChanged = { updatedContextualTopBarOverride = it },
            showRemoveOption = true,
            sharedElementInstanceKey = "main_updated",
            isContentTypeFilterVisible = true,
            isSourceTagFilterVisible = true,
            onRemoveSelection = { ids -> viewModel.remove(ids) },
            onNavigateToDetails = { _, content, sharedKey ->
                navigateToDetailsWithContent(content, sharedKey)
            },
            onFilterRailOverrideChanged = {},
            onAddMenuProvider = { _, _, _ ->
                object : androidx.core.view.MenuProvider {
                    override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
                        menuInflater.inflate(org.skepsun.kototoro.R.menu.opt_list, menu)
                    }

                    override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean = when (menuItem.itemId) {
                        org.skepsun.kototoro.R.id.action_refresh -> {
                            viewModel.onRefresh()
                            true
                        }
                        org.skepsun.kototoro.R.id.action_list_mode -> {
                            appRouter.showListConfigSheet(org.skepsun.kototoro.list.ui.config.ListConfigSection.Updated)
                            true
                        }
                        else -> false
                    }
                }
            },
            showQuickFilterInline = true,
            quickFilterOverride = headerQuickFilter,
        )
    }
}

@Composable
internal fun HistoryTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    activity: FragmentActivity,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    rootView: android.view.View,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    bottomBarOffsetPx: Float,
    bottomBarHeightPx: Int,
    isLandscapeNavigation: Boolean,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    navigateToDetailsWithOrigin: (org.skepsun.kototoro.details.ui.model.DetailsOrigin, String?) -> Unit,
) {
    val viewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.history.ui.HistoryListViewModel>("history")
    val context = LocalContext.current
    val entryPoint = remember(context.applicationContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BaseApp.BaseAppEntryPoint::class.java,
            )
        }.getOrNull()
    }
    val historyPagingItems = viewModel.pagingContent.collectAsLazyPagingItems()
    val items = historyPagingItems.itemSnapshotList.items
    val headerQuickFilter by viewModel.headerQuickFilter.collectAsStateWithLifecycle()
    val listMode by viewModel.listMode.collectAsStateWithLifecycle()
    val isStatsEnabled by viewModel.isStatsEnabled.collectAsStateWithLifecycle()
    val isResumeEnabled by viewModel.isResumeEnabled.collectAsStateWithLifecycle()
    val gridScale by viewModel.gridScale.collectAsStateWithLifecycle()
    val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()
    var selectedItemsIds by remember { mutableStateOf(emptySet<Long>()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingMarkAsReadItems by remember { mutableStateOf<List<Content>?>(null) }
    val selectedModels = remember(items, selectedItemsIds) {
        items
            .filterIsInstance<org.skepsun.kototoro.list.ui.model.ContentListModel>()
            .filter { it.id in selectedItemsIds }
    }

    DisposableEffect(onContextualMenuActionsChanged) {
        onContextualMenuActionsChanged(
            RouteScopedTopBarMenuActions(
                ownerRoute = TOP_BAR_OWNER_HISTORY,
                actions = listOf(
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.clear_history,
                        org.skepsun.kototoro.R.drawable.ic_clear_all,
                    ) {
                        showClearDialog = true
                    },
                ),
            ),
        )
        onDispose {
            onContextualMenuActionsChanged(
                RouteScopedTopBarMenuActions(
                    ownerRoute = TOP_BAR_OWNER_HISTORY,
                    actions = emptyList(),
                ),
            )
        }
    }

    BackHandler(enabled = selectedItemsIds.isNotEmpty()) {
        selectedItemsIds = emptySet()
    }

    SideEffect {
        if (selectedItemsIds.isNotEmpty()) {
            onExploreSourceSelectionTopBarChanged(
                RouteScopedTopBarOverrideState(
                    TOP_BAR_OWNER_HISTORY,
                    LayeredTopBarOverrideState(
                        contextualOverrideState = ContentSelectionTopBarOverrideState(
                            selectedCount = selectedItemsIds.size,
                            isAllNonLocal = selectedModels.none { it.manga.isLocal },
                            isSingleSelection = selectedItemsIds.size == 1,
                            showRemoveOption = true,
                            supportedActions = setOf(
                                org.skepsun.kototoro.list.ui.compose.SelectionAction.SELECT_ALL,
                                org.skepsun.kototoro.list.ui.compose.SelectionAction.REMOVE,
                                org.skepsun.kototoro.list.ui.compose.SelectionAction.SAVE,
                                org.skepsun.kototoro.list.ui.compose.SelectionAction.FAVOURITE,
                                org.skepsun.kototoro.list.ui.compose.SelectionAction.MARK_AS_COMPLETED,
                            ),
                            onClearSelection = { selectedItemsIds = emptySet() },
                            onActionClick = { action ->
                                when (action) {
                                    org.skepsun.kototoro.list.ui.compose.SelectionAction.SELECT_ALL -> {
                                        selectedItemsIds = items
                                            .filterIsInstance<org.skepsun.kototoro.list.ui.model.ContentListModel>()
                                            .mapTo(linkedSetOf()) { it.id }
                                    }
                                    org.skepsun.kototoro.list.ui.compose.SelectionAction.REMOVE -> {
                                        viewModel.removeFromHistory(selectedItemsIds)
                                        selectedItemsIds = emptySet()
                                    }
                                    org.skepsun.kototoro.list.ui.compose.SelectionAction.SAVE -> {
                                        appRouter.showDownloadDialog(selectedModels.map { it.manga }, rootView)
                                        selectedItemsIds = emptySet()
                                    }
                                    org.skepsun.kototoro.list.ui.compose.SelectionAction.FAVOURITE -> {
                                        appRouter.showFavoriteDialog(selectedModels.map { it.manga })
                                        selectedItemsIds = emptySet()
                                    }
                                    org.skepsun.kototoro.list.ui.compose.SelectionAction.EDIT_OVERRIDE -> {
                                        selectedModels.singleOrNull()?.manga?.let(appRouter::openContentOverrideConfig)
                                        selectedItemsIds = emptySet()
                                    }
                                    org.skepsun.kototoro.list.ui.compose.SelectionAction.MARK_AS_COMPLETED -> {
                                        pendingMarkAsReadItems = selectedModels.map { it.manga }
                                        selectedItemsIds = emptySet()
                                    }
                                    else -> Unit
                                }
                            },
                        ),
                    ),
                ),
            )
        } else {
            onExploreSourceSelectionTopBarChanged(
                RouteScopedTopBarOverrideState(
                    TOP_BAR_OWNER_HISTORY,
                    LayeredTopBarOverrideState(),
                ),
            )
        }
    }

    LaunchedEffect(viewModel.onOpenReader, appRouter) {
        viewModel.onOpenReader.collect { event ->
            event?.consume { content ->
                appRouter.openReader(content)
            }
        }
    }

    LaunchedEffect(viewModel.onActionDone) {
        val observer = org.skepsun.kototoro.core.ui.util.ReversibleActionObserver(rootView)
        viewModel.onActionDone.collect { event ->
            event?.consume(observer)
        }
    }

    LaunchedEffect(viewModel.onError, activity) {
        val host = activity.window.decorView.rootView
        val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseComposeActivity)?.exceptionResolver
        val observer = org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver(host, null, resolver, null)
        viewModel.onError.collect { event ->
            event?.consume(observer)
        }
    }

    DisposableEffect(mainActivity, viewModel, selectedGroupTab, selectedSourceTags) {
        val callback = object : SearchBarFilterViewController.Callback {
            override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                viewModel.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
            }

            override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> = selectedSourceTags

            override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                viewModel.setSelectedSourceTags(
                    when {
                        tag == null -> emptySet()
                        tag in selectedSourceTags -> selectedSourceTags - tag
                        else -> selectedSourceTags + tag
                    },
                )
            }
        }
        mainActivity?.setActiveFilterCallback(callback)
        onDispose {
            mainActivity?.clearActiveFilterCallback(callback)
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        org.skepsun.kototoro.history.ui.compose.HistoryScreen(
            contentPadding = contentPadding,
            items = items,
            pagingItems = historyPagingItems,
            headerQuickFilter = headerQuickFilter,
            listMode = listMode,
            isRefreshing = false,
            pullRefreshEnabled = false,
            isStatsEnabled = isStatsEnabled,
            gridScale = gridScale,
            selectedItemsIds = selectedItemsIds,
            onRefresh = { viewModel.onRefresh() },
            onLoadMore = { viewModel.requestMoreItems() },
            onPrepareItemTransition = { _, _ -> },
            onItemClick = { item ->
                if (selectedItemsIds.isNotEmpty()) {
                    selectedItemsIds = if (item.id in selectedItemsIds) {
                        selectedItemsIds - item.id
                    } else {
                        selectedItemsIds + item.id
                    }
                } else {
                    val content = item.toContentWithOverride()
                    val sharedKey = contentCoverSharedKey(item.source.name, item.coverUrl.orEmpty())
                    val entityId = viewModel.resolveEntityIdForUiItemId(item.id)
                    val preferredLocalMangaId = viewModel.resolvePreferredLocalMangaIdForUiItemId(item.id)
                    if (entityId != null) {
                        navigateToDetailsWithOrigin(
                            org.skepsun.kototoro.details.ui.model.DetailsOrigin.EntityGraph(
                                entityId = entityId,
                                preferredLocalMangaId = preferredLocalMangaId ?: content.id,
                                initialProjectionLocalMangaId = content.id,
                            ),
                            sharedKey,
                        )
                    } else {
                        navigateToDetailsWithContent(content, sharedKey)
                    }
                }
            },
            onItemLongClick = { item ->
                selectedItemsIds = if (item.id in selectedItemsIds) {
                    selectedItemsIds - item.id
                } else {
                    selectedItemsIds + item.id
                }
            },
            onClearSelection = { selectedItemsIds = emptySet() },
            onSelectionAction = { action ->
                when (action) {
                    org.skepsun.kototoro.list.ui.compose.SelectionAction.REMOVE -> {
                        viewModel.removeFromHistory(selectedItemsIds)
                        selectedItemsIds = emptySet()
                    }
                    org.skepsun.kototoro.list.ui.compose.SelectionAction.SAVE -> {
                        appRouter.showDownloadDialog(selectedModels.map { it.manga }, rootView)
                        selectedItemsIds = emptySet()
                    }
                    org.skepsun.kototoro.list.ui.compose.SelectionAction.FAVOURITE -> {
                        appRouter.showFavoriteDialog(selectedModels.map { it.manga })
                        selectedItemsIds = emptySet()
                    }
                    org.skepsun.kototoro.list.ui.compose.SelectionAction.EDIT_OVERRIDE -> {
                        selectedModels.singleOrNull()?.manga?.let(appRouter::openContentOverrideConfig)
                        selectedItemsIds = emptySet()
                    }
                    org.skepsun.kototoro.list.ui.compose.SelectionAction.MARK_AS_COMPLETED -> {
                        pendingMarkAsReadItems = selectedModels.map { it.manga }
                        selectedItemsIds = emptySet()
                    }
                    else -> Unit
                }
            },
            onStatsClick = { appRouter.openStatistic() },
            onContinueReadingClick = { viewModel.openLastReader() },
            onQuickFilterOptionClick = viewModel::toggleFilterOption,
            showContinueReadingButton = isResumeEnabled,
            showQuickFilterInline = true,
            showInlineSelectionTopBar = false,
        )

        if (showClearDialog) {
            org.skepsun.kototoro.history.ui.compose.ClearHistoryDialog(
                onDismissRequest = { showClearDialog = false },
                onConfirm = { option ->
                    when (option) {
                        org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.LAST_2_HOURS -> {
                            viewModel.clearHistory(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS))
                        }
                        org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.TODAY -> {
                            viewModel.clearHistory(
                                java.time.LocalDate.now()
                                    .atStartOfDay(java.time.ZoneId.systemDefault())
                                    .toInstant(),
                            )
                        }
                        org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.NOT_IN_FAVORITES -> {
                            viewModel.removeNotFavorite()
                        }
                        org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.CLEAR_ALL -> {
                            viewModel.clearHistory(null)
                        }
                    }
                },
            )
        }

        pendingMarkAsReadItems?.let { itemsToMark ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingMarkAsReadItems = null },
                title = {
                    androidx.compose.material3.Text(
                        text = stringResource(org.skepsun.kototoro.R.string.mark_as_completed),
                    )
                },
                text = {
                    androidx.compose.material3.Text(
                        text = stringResource(org.skepsun.kototoro.R.string.mark_as_completed_prompt),
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            viewModel.markAsRead(itemsToMark.toSet())
                            pendingMarkAsReadItems = null
                        },
                    ) {
                        androidx.compose.material3.Text(text = stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingMarkAsReadItems = null }) {
                        androidx.compose.material3.Text(text = stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
internal fun FavoritesTopLevelRouteContent(
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    entityOrganizeResultSource: FavoritesEntityOrganizeResultSource,
    mainActivity: MainActivity?,
    appRouter: org.skepsun.kototoro.core.nav.AppRouter,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit,
    onContextualMenuActionsChanged: (RouteScopedTopBarMenuActions) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    navigateToDetailsWithOrigin: (org.skepsun.kototoro.details.ui.model.DetailsOrigin, String?) -> Unit,
) {
    val viewModel = spaceBoundHiltViewModel<org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel>(
        "favorites",
    )
    val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by viewModel.globalFavoritesState.selectedSourceTags.collectAsStateWithLifecycle()
    var entityOrganizeRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    var nextFavoritesDialogId by remember { mutableLongStateOf(0L) }
    var pendingFavoritesDialog by remember { mutableStateOf<PendingFavoritesDialog?>(null) }
    var favoritesSelectionDialog by remember { mutableStateOf<FavoritesSelectionDialogState?>(null) }

    fun showToast(messageRes: Int) {
        android.widget.Toast.makeText(context, messageRes, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun showToast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun showImportDialog() {
        nextFavoritesDialogId += 1
        pendingFavoritesDialog = PendingFavoritesDialog(nextFavoritesDialogId, isSync = false)
        favoritesSelectionDialog = null
    }

    LaunchedEffect(pendingFavoritesDialog) {
        val request = pendingFavoritesDialog ?: return@LaunchedEffect
        val candidates = if (request.isSync) {
            viewModel.loadSyncCandidates()
        } else {
            viewModel.loadImportCandidates()
        }
        currentCoroutineContext().ensureActive()
        if (candidates.isEmpty()) {
            if (pendingFavoritesDialog == request) {
                pendingFavoritesDialog = null
                showToast(org.skepsun.kototoro.R.string.import_favourites_no_available)
            }
            return@LaunchedEffect
        }
        if (pendingFavoritesDialog != request) {
            return@LaunchedEffect
        }
        favoritesSelectionDialog = FavoritesSelectionDialogState(
            request = request,
            candidates = candidates,
            selectedIndices = candidates.indices.toSet(),
        )
    }

    LaunchedEffect(entityOrganizeResultSource) {
        entityOrganizeResultSource.refreshSignals.collect {
            if (!entityOrganizeResultSource.consumeRefresh()) {
                return@collect
            }
            entityOrganizeRefreshGeneration += 1
        }
    }

    LaunchedEffect(entityOrganizeResultSource) {
        entityOrganizeResultSource.messageSignals.collect {
            val message = entityOrganizeResultSource.consumeMessage()
            if (message == null) {
                return@collect
            }
            viewModel.notifyEntityOrganizeResult(message)
        }
    }

    fun showSyncDialog() {
        nextFavoritesDialogId += 1
        pendingFavoritesDialog = PendingFavoritesDialog(nextFavoritesDialogId, isSync = true)
        favoritesSelectionDialog = null
    }

    favoritesSelectionDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = {
                favoritesSelectionDialog = null
                pendingFavoritesDialog = null
            },
            title = {
                Text(
                    text = stringResource(
                        if (dialog.request.isSync) {
                            org.skepsun.kototoro.R.string.sync_favourites_title
                        } else {
                            org.skepsun.kototoro.R.string.import_favourites_title
                        },
                    ),
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (dialog.request.isSync) {
                        Text(
                            text = stringResource(org.skepsun.kototoro.R.string.sync_favourites_warning),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        itemsIndexed(dialog.candidates) { index, candidate ->
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val selected = dialog.selectedIndices.toMutableSet()
                                        if (!selected.add(index)) {
                                            selected.remove(index)
                                        }
                                        favoritesSelectionDialog = dialog.copy(selectedIndices = selected)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = index in dialog.selectedIndices,
                                    onCheckedChange = { checked ->
                                        val selected = dialog.selectedIndices.toMutableSet()
                                        if (checked) {
                                            selected.add(index)
                                        } else {
                                            selected.remove(index)
                                        }
                                        favoritesSelectionDialog = dialog.copy(selectedIndices = selected)
                                    },
                                )
                                Text(
                                    text = candidate.title,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedCandidates = dialog.candidates.filterIndexed { index, _ ->
                            index in dialog.selectedIndices
                        }
                        favoritesSelectionDialog = null
                        pendingFavoritesDialog = null
                        if (dialog.request.isSync) {
                            viewModel.syncFavorites(selectedCandidates)
                        } else {
                            viewModel.importFavorites(selectedCandidates)
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        favoritesSelectionDialog = null
                        pendingFavoritesDialog = null
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    DisposableEffect(appRouter, viewModel) {
        onContextualMenuActionsChanged(
            RouteScopedTopBarMenuActions(
                ownerRoute = TOP_BAR_OWNER_FAVORITES,
                actions = listOf(
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.reset_filter,
                        org.skepsun.kototoro.R.drawable.ic_revert,
                    ) {
                        viewModel.resetFilters()
                    },
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.favourites_categories,
                        org.skepsun.kototoro.R.drawable.ic_tag,
                    ) {
                        appRouter.openFavoriteCategories()
                    },
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.entity_organize_title,
                        org.skepsun.kototoro.R.drawable.ic_select_group,
                    ) {
                        appRouter.openEntityOrganizeSettings()
                    },
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.import_favourites,
                        org.skepsun.kototoro.R.drawable.ic_import,
                    ) {
                        showImportDialog()
                    },
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.sync_favourites,
                        org.skepsun.kototoro.R.drawable.ic_sync,
                    ) {
                        showSyncDialog()
                    },
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.duplicates_finder,
                        org.skepsun.kototoro.R.drawable.ic_search,
                    ) {
                        viewModel.openDuplicatesFinder()
                    },
                ),
            ),
        )
        onDispose {
            onContextualMenuActionsChanged(
                RouteScopedTopBarMenuActions(
                    ownerRoute = TOP_BAR_OWNER_FAVORITES,
                    actions = emptyList(),
                ),
            )
        }
    }

    LaunchedEffect(viewModel.importMessages) {
        viewModel.importMessages.collect { event ->
            event?.consume(eventCollector { message ->
                showToast(message)
            })
        }
    }

    LaunchedEffect(viewModel.syncMessages) {
        viewModel.syncMessages.collect { event ->
            event?.consume(eventCollector { message ->
                showToast(message)
            })
        }
    }

    LaunchedEffect(viewModel.organizeMessages) {
        viewModel.organizeMessages.collect { event ->
            event?.consume(eventCollector { message ->
                showToast(message)
            })
        }
    }

    DisposableEffect(mainActivity, viewModel, selectedGroupTab, selectedSourceTags) {
        val callback = object : SearchBarFilterViewController.Callback {
            override fun isSourceTagFilterVisible(): Boolean = true

            override fun getSourceTagEntries(): List<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                org.skepsun.kototoro.explore.ui.model.SourceTag.quickFilterEntries

            override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                viewModel.setSelectedGroupTab(tab)
            }

            override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                selectedSourceTags

            override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                when {
                    tag == null -> viewModel.globalFavoritesState.clearSourceTags()
                    tag in selectedSourceTags -> {
                        viewModel.globalFavoritesState.setSelectedSourceTags(selectedSourceTags - tag)
                    }
                    else -> {
                        viewModel.globalFavoritesState.setSelectedSourceTags(selectedSourceTags + tag)
                    }
                }
            }
        }
        mainActivity?.setActiveFilterCallback(callback)
        onDispose {
            mainActivity?.clearActiveFilterCallback(callback)
        }
    }

    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope) {
        KototoroFavoritesHostRoute(
            appRouter = appRouter,
            contentPadding = contentPadding,
            refreshGeneration = entityOrganizeRefreshGeneration,
            consumeOrganizeMessages = false,
            onOpenEntityOrganize = { selectedIds ->
                appRouter.openEntityOrganizeSettings(selectedIds)
            },
            onNavigateToDetails = { content, sharedKey ->
                navigateToDetailsWithContent(content, sharedKey)
            },
            onNavigateToEntityDetails = { origin, sharedKey ->
                navigateToDetailsWithOrigin(origin, sharedKey)
            },
            registerFilterCallback = false,
            onTopBarOverrideChanged = {
                onExploreSourceSelectionTopBarChanged(
                    RouteScopedTopBarOverrideState(TOP_BAR_OWNER_FAVORITES, it),
                )
            },
            viewModel = viewModel,
        )
    }
}
