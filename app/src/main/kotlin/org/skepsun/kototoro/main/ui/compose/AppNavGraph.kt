package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import dagger.hilt.android.EntryPointAccessors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.compose.KototoroExploreHostRoute
import org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState
import org.skepsun.kototoro.favourites.ui.compose.KototoroFavoritesHostRoute
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
import androidx.compose.runtime.mutableStateOf
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.resolveSourceTitleForUi
import org.skepsun.kototoro.details.ui.compose.DetailsScreen
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.compose.handleDetailsAction
import org.skepsun.kototoro.parsers.model.Content
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailItem
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarTabItem
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.selectedFirst

private fun <T> eventCollector(block: suspend (T) -> Unit): FlowCollector<T> = FlowCollector { value ->
    block(value)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isMainRouteTransition(): Boolean {
    return initialState.destination.isMainRoute() && targetState.destination.isMainRoute()
}

private fun NavDestination.mainRouteOrder(): Int = when {
    hasRoute<HomeRoute>() -> 0
    hasRoute<HistoryRoute>() -> 1
    hasRoute<FavoritesRoute>() -> 2
    hasRoute<ExploreRoute>() -> 3
    hasRoute<DiscoverRoute>() -> 4
    hasRoute<FeedRoute>() -> 5
    hasRoute<LocalRoute>() -> 6
    hasRoute<SuggestionsRoute>() -> 7
    hasRoute<BookmarksRoute>() -> 8
    hasRoute<UpdatedRoute>() -> 9
    else -> Int.MAX_VALUE
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.mainRouteFadeIn(): EnterTransition {
    if (!isMainRouteTransition()) return EnterTransition.None
    return fadeIn(
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        initialAlpha = 0.82f,
    )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.mainRouteFadeOut(): ExitTransition {
    if (!isMainRouteTransition()) return ExitTransition.None
    return fadeOut(
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        targetAlpha = 0.88f,
    )
}

@Composable
private fun MainRouteScene(
    landscapeStartPadding: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = landscapeStartPadding),
    ) {
        content()
    }
}

private fun buildFavoriteCategoryTabsState(
    items: List<ListModel>,
    allTitle: String,
    onClearSelection: () -> Unit,
    onCategorySelected: (ListFilterOption.Favorite) -> Unit,
): CompactTabsTopBarOverrideState? {
    val quickFilter = items.firstOrNull { it is QuickFilter } as? QuickFilter ?: return null
    val favoriteOptions = quickFilter.items.mapNotNull { chip ->
        chip.data as? ListFilterOption.Favorite
    }
    if (favoriteOptions.isEmpty()) {
        return null
    }
    val selectedCategoryId = favoriteOptions.firstOrNull { option ->
        quickFilter.items.any { chip -> chip.data == option && chip.isChecked }
    }?.category?.id ?: NO_ID
    val optionsById = favoriteOptions.associateBy { it.category.id }
    return CompactTabsTopBarOverrideState(
        items = buildList {
            add(CompactTopBarTabItem(id = NO_ID, title = allTitle))
            favoriteOptions.forEach { option ->
                add(
                    CompactTopBarTabItem(
                        id = option.category.id,
                        title = option.category.title,
                    ),
                )
            }
        },
        selectedItemId = selectedCategoryId,
        onItemSelected = { categoryId ->
            if (categoryId == NO_ID || categoryId == selectedCategoryId) {
                onClearSelection()
            } else {
                optionsById[categoryId]?.let(onCategorySelected)
            }
        },
    )
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
    hasRoute<HomeRoute>() ||
        hasRoute<DiscoverRoute>() ||
        hasRoute<HistoryRoute>() ||
        hasRoute<FavoritesRoute>() ||
        hasRoute<ExploreRoute>() ||
        hasRoute<FeedRoute>() ||
        hasRoute<LocalRoute>() ||
        hasRoute<SuggestionsRoute>() ||
        hasRoute<BookmarksRoute>() ||
        hasRoute<UpdatedRoute>()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: Any = HomeRoute,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    bottomBarOffsetPx: Float = 0f,
    bottomBarHeightPx: Int = 0,
    pageSaveHelper: org.skepsun.kototoro.reader.ui.PageSaveHelper? = null,
    modifier: Modifier = Modifier,
    onExploreSourceSelectionTopBarChanged: (TopBarOverrideState?) -> Unit = {},
    onContextualMenuActionsChanged: (List<KototoroTopBarMenuAction>) -> Unit = {},
    onOpenSearch: (SearchNavigationRequest) -> Unit = {},
    onDetailsTransitionRequested: () -> Unit = {},
    onDetailsReturnTransitionRequested: () -> Unit = {},
    isLandscapeNavigation: Boolean = false,
) {
    val activity = LocalContext.current as FragmentActivity
    val appRouter = activity.router
    val mainActivity = activity as? MainActivity
    val rootView = LocalView.current
    val density = LocalDensity.current
    val landscapeStartPadding = if (isLandscapeNavigation) {
        with(density) { bottomBarHeightPx.toDp() }
    } else {
        0.dp
    }
    val navigateToDetailsWithContent = remember(navController) {
        { content: Content, sharedElementKey: String? ->
            onDetailsTransitionRequested()
            PendingDetailsNavigation.set(content, sharedElementKey)
            navController.navigate(DetailsRoute)
        }
    }
    val navigateToDetailsWithOrigin = remember(navController) {
        { origin: org.skepsun.kototoro.details.ui.model.DetailsOrigin, sharedElementKey: String? ->
            onDetailsTransitionRequested()
            PendingDetailsNavigation.set(origin, sharedElementKey)
            navController.navigate(DetailsRoute)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { mainRouteFadeIn() },
        exitTransition = { mainRouteFadeOut() },
        popEnterTransition = { mainRouteFadeIn() },
        popExitTransition = { mainRouteFadeOut() },
    ) {
        composable<HomeRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<HomeViewModel>()
            val state by viewModel.summaryState.collectAsStateWithLifecycle()
            val isRandomLoading by viewModel.isRandomLoading.collectAsStateWithLifecycle()

            androidx.compose.runtime.LaunchedEffect(viewModel.onOpenContent, navigateToDetailsWithContent) {
                viewModel.onOpenContent.collect { event ->
                    event?.consume { content ->
                        navigateToDetailsWithContent(content, null)
                    }
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
                            }
                        )
                    }

                    override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> = state.selectedSourceTags

                    override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                        val current = state.selectedSourceTags
                        viewModel.setSelectedSourceTags(
                            when {
                                tag == null -> emptySet()
                                tag in current -> current - tag
                                else -> current + tag
                            }
                        )
                    }
                }
                mainActivity?.setActiveFilterCallback(callback)
                onDispose {
                    mainActivity?.clearActiveFilterCallback(callback)
                }
            }

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                val onHomeContentClick = remember(navigateToDetailsWithContent) {
                    { content: Content, _: Rect?, sharedElementKey: String? ->
                        navigateToDetailsWithContent(content, sharedElementKey)
                    }
                }
                val markReturnHomeOnBack = remember(navController) {
                    {
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set(RETURN_HOME_ON_BACK_KEY, true)
                        Unit
                    }
                }
                val onHomeSettingsClick = remember(appRouter) { { appRouter.openSettings() } }
                val onHomeReaderSettingsClick = remember(appRouter) { { appRouter.openReaderSettings() } }
                val onHomeSyncSettingsClick = remember(appRouter) { { appRouter.openSyncSettings() } }
                val onHomeViewAllRecentClick = remember(navController, markReturnHomeOnBack) {
                    {
                        navController.navigate(HistoryRoute) {
                            launchSingleTop = true
                        }
                        markReturnHomeOnBack()
                    }
                }
                val onHomeViewAllUpdatesClick = remember(navController, markReturnHomeOnBack) {
                    {
                        navController.navigate(UpdatedRoute) {
                            launchSingleTop = true
                        }
                        markReturnHomeOnBack()
                    }
                }
                val onHomeViewAllRecommendationsClick = remember(navController, markReturnHomeOnBack) {
                    {
                        navController.navigate(SuggestionsRoute) {
                            launchSingleTop = true
                        }
                        markReturnHomeOnBack()
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
                val onHomeSourceSettingsClick = remember(appRouter) { { appRouter.openSourcesSettings() } }
                val onHomeLibraryOpenClick = remember(navController, markReturnHomeOnBack) {
                    {
                        navController.navigate(FavoritesRoute) {
                            launchSingleTop = true
                        }
                        markReturnHomeOnBack()
                    }
                }
                val onHomeBookmarksClick = remember(navController, markReturnHomeOnBack) {
                    {
                        navController.navigate(BookmarksRoute) {
                            launchSingleTop = true
                        }
                        markReturnHomeOnBack()
                    }
                }
                val onHomeLocalClick = remember(navController, markReturnHomeOnBack) {
                    {
                        navController.navigate(LocalRoute) {
                            launchSingleTop = true
                        }
                        markReturnHomeOnBack()
                    }
                }
                val onHomeDownloadsClick = remember(appRouter) { { appRouter.openDownloads() } }
                val onHomeRandomClick = remember(viewModel) { { viewModel.openRandom() } }
                val onHomeAutoTranslateClick = remember(appRouter) { { appRouter.openTranslationSettings() } }
                val homeActions = remember(
                    onHomeSettingsClick,
                    onHomeReaderSettingsClick,
                    onHomeSyncSettingsClick,
                    onHomeViewAllRecentClick,
                    onHomeViewAllUpdatesClick,
                    onHomeViewAllRecommendationsClick,
                    onHomeRecentSearchClick,
                    onHomeSourceSettingsClick,
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
                        onSyncSettingsClick = onHomeSyncSettingsClick,
                        onViewAllRecentClick = onHomeViewAllRecentClick,
                        onViewAllUpdatesClick = onHomeViewAllUpdatesClick,
                        onViewAllRecommendationsClick = onHomeViewAllRecommendationsClick,
                        onRecentSearchClick = onHomeRecentSearchClick,
                        onSourceSettingsClick = onHomeSourceSettingsClick,
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
        }
        composable<DiscoverRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val exploreViewModel = hiltViewModel<org.skepsun.kototoro.explore.ui.ExploreViewModel>()
            val selectedGroupTab by exploreViewModel.currentGroupTab.collectAsStateWithLifecycle()
            val selectedSourceTags by exploreViewModel.currentSourceTags.collectAsStateWithLifecycle()

            DisposableEffect(mainActivity, exploreViewModel, selectedGroupTab, selectedSourceTags) {
                val callback = object : SearchBarFilterViewController.Callback {
                    override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

                    override fun onContentTypeSelected(tab: BrowseGroupTab) {
                        exploreViewModel.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
                    }

                    override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> = selectedSourceTags

                    override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                        exploreViewModel.setSelectedSourceTags(
                            when {
                                tag == null -> emptySet()
                                tag in selectedSourceTags -> selectedSourceTags - tag
                                else -> selectedSourceTags + tag
                            }
                        )
                    }
                }
                mainActivity?.setActiveFilterCallback(callback)
                onDispose {
                    mainActivity?.clearActiveFilterCallback(callback)
                }
            }

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                org.skepsun.kototoro.explore.ui.compose.KototoroExploreHostRoute(
                    appRouter = appRouter,
                    contentPadding = contentPadding,
                    exploreViewModel = exploreViewModel,
                    onSourceSelectionTopBarChanged = {
                        onExploreSourceSelectionTopBarChanged(
                            RouteScopedTopBarOverrideState(TOP_BAR_OWNER_DISCOVER, it),
                        )
                    },
                    onNavigateToDetails = navigateToDetailsWithOrigin,
                )
            }
            }
        }
        composable<HistoryRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.history.ui.HistoryListViewModel>()
            val context = LocalContext.current
            val entryPoint = remember(context.applicationContext) {
                runCatching {
                    EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        BaseApp.BaseAppEntryPoint::class.java,
                    )
                }.getOrNull()
            }
            val items by viewModel.content.collectAsStateWithLifecycle()
            val listMode by viewModel.listMode.collectAsStateWithLifecycle()
            val isStatsEnabled by viewModel.isStatsEnabled.collectAsStateWithLifecycle()
            val isResumeEnabled by viewModel.isResumeEnabled.collectAsStateWithLifecycle()
            val gridScale by viewModel.gridScale.collectAsStateWithLifecycle()
            val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
            val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()
            val historyFilterRailState = remember(items, context, entryPoint, viewModel) {
                val quickFilter = items.firstOrNull { it is org.skepsun.kototoro.list.ui.model.QuickFilter } as? org.skepsun.kototoro.list.ui.model.QuickFilter
                quickFilter?.let { filter ->
                    CompactFilterRailOverrideState(
                        items = filter.items.mapIndexedNotNull { index, chip ->
                            val option = chip.data as? ListFilterOption ?: return@mapIndexedNotNull null
                            val sourceOption = option as? ListFilterOption.Source
                            val title = when {
                                sourceOption != null -> resolveSourceTitleForUi(
                                    context = context,
                                    source = sourceOption.mangaSource,
                                    entryPoint = entryPoint,
                                )
                                chip.titleResId != 0 -> context.getString(chip.titleResId)
                                !chip.title.isNullOrBlank() -> chip.title.toString()
                                else -> return@mapIndexedNotNull null
                            }
                            CompactFilterRailItem(
                                id = "${option::class.qualifiedName}:${option.hashCode()}:$index",
                                title = title,
                                isSelected = chip.isChecked,
                                source = sourceOption?.mangaSource,
                                onClick = { viewModel.toggleFilterOption(option) },
                            )
                        }.selectedFirst(),
                    )
                }
            }
            var selectedItemsIds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptySet<Long>()) }
            var showClearDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val selectedModels = remember(items, selectedItemsIds) {
                items
                    .filterIsInstance<org.skepsun.kototoro.list.ui.model.ContentListModel>()
                    .filter { it.id in selectedItemsIds }
            }
            DisposableEffect(onContextualMenuActionsChanged) {
                onContextualMenuActionsChanged(
                    listOf(
                        KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.clear_history) {
                            showClearDialog = true
                        },
                    ),
                )
                onDispose {
                    onContextualMenuActionsChanged(emptyList())
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
                                filterRailState = null,
                                contextualOverrideState = ContentSelectionTopBarOverrideState(
                                    selectedCount = selectedItemsIds.size,
                                    isAllNonLocal = selectedModels.none { it.manga.isLocal },
                                    isSingleSelection = selectedItemsIds.size == 1,
                                    showRemoveOption = true,
                                    supportedActions = setOf(
                                        org.skepsun.kototoro.list.ui.compose.SelectionAction.SELECT_ALL,
                                        org.skepsun.kototoro.list.ui.compose.SelectionAction.REMOVE,
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

            androidx.compose.runtime.LaunchedEffect(viewModel.onOpenReader, appRouter) {
                viewModel.onOpenReader.collect { event ->
                    event?.consume { content ->
                        appRouter.openReader(content)
                    }
                }
            }

            androidx.compose.runtime.LaunchedEffect(viewModel.onActionDone) {
                val observer = org.skepsun.kototoro.core.ui.util.ReversibleActionObserver(rootView)
                viewModel.onActionDone.collect { event ->
                    event?.consume(observer)
                }
            }

            androidx.compose.runtime.LaunchedEffect(viewModel.onError) {
                val host = activity.window.decorView.rootView
                val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseActivity<*>)?.exceptionResolver
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
                            }
                        )
                    }
                }
                mainActivity?.setActiveFilterCallback(callback)
                onDispose {
                    mainActivity?.clearActiveFilterCallback(callback)
                }
            }

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                org.skepsun.kototoro.history.ui.compose.HistoryScreen(
                    contentPadding = contentPadding,
                    items = items,
                    listMode = listMode,
                    isRefreshing = false,
                    pullRefreshEnabled = false,
                    isStatsEnabled = isStatsEnabled,
                    gridScale = gridScale,
                    selectedItemsIds = selectedItemsIds,
                    onRefresh = { viewModel.onRefresh() },
                    onLoadMore = { viewModel.requestMoreItems() },
                    onPrepareItemTransition = { item, coverBounds ->
                    },
                    onItemClick = { item ->
                        if (selectedItemsIds.isNotEmpty()) {
                            selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                        } else {
                            val content = item.toContentWithOverride()
                            navigateToDetailsWithContent(
                                content,
                                contentCoverSharedKey(item.source.name, item.coverUrl.orEmpty()),
                            )
                        }
                    },
                    onItemLongClick = { item ->
                        selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                    },
                    onClearSelection = { selectedItemsIds = emptySet() },
                    onSelectionAction = { action ->
                        if (action == org.skepsun.kototoro.list.ui.compose.SelectionAction.REMOVE) {
                            viewModel.removeFromHistory(selectedItemsIds)
                            selectedItemsIds = emptySet()
                        }
                    },
                    onStatsClick = { appRouter.openStatistic() },
                    onContinueReadingClick = { viewModel.openLastReader() },
                    onQuickFilterOptionClick = viewModel::toggleFilterOption,
                    showContinueReadingButton = isResumeEnabled && !isLandscapeNavigation,
                    showQuickFilterInline = true,
                    bottomBarOffsetPx = bottomBarOffsetPx,
                    bottomBarHeightPx = bottomBarHeightPx,
                    showInlineSelectionTopBar = false,
                )

                if (showClearDialog) {
                    org.skepsun.kototoro.history.ui.compose.ClearHistoryDialog(
                        onDismissRequest = { showClearDialog = false },
                        onConfirm = { option ->
                            when(option) {
                                org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.LAST_2_HOURS -> viewModel.clearHistory(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS))
                                org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.TODAY -> viewModel.clearHistory(java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
                                org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.NOT_IN_FAVORITES -> viewModel.removeNotFavorite()
                                org.skepsun.kototoro.history.ui.compose.ClearHistoryOption.CLEAR_ALL -> viewModel.clearHistory(null)
                            }
                        }
                    )
                }
            }
            }
        }
        composable<FavoritesRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel>()
            val selectedGroupTab by viewModel.globalFavoritesState.selectedGroupTab.collectAsStateWithLifecycle()
            val selectedSourceTags by viewModel.globalFavoritesState.selectedSourceTags.collectAsStateWithLifecycle()
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current
            fun showToast(messageRes: Int) {
                android.widget.Toast.makeText(context, messageRes, android.widget.Toast.LENGTH_SHORT).show()
            }

            fun showToast(message: String) {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }

            fun showImportDialog(scope: CoroutineScope) {
                scope.launch {
                    val candidates = viewModel.loadImportCandidates()
                    if (candidates.isEmpty()) {
                        showToast(org.skepsun.kototoro.R.string.import_favourites_no_available)
                        return@launch
                    }
                    val checked = BooleanArray(candidates.size) { true }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle(org.skepsun.kototoro.R.string.import_favourites_title)
                        .setMultiChoiceItems(candidates.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                            checked[which] = isChecked
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            viewModel.importFavorites(candidates.filterIndexed { index, _ -> checked[index] })
                        }
                        .show()
                }
            }

            fun showSyncDialog(scope: CoroutineScope) {
                scope.launch {
                    val candidates = viewModel.loadSyncCandidates()
                    if (candidates.isEmpty()) {
                        showToast(org.skepsun.kototoro.R.string.import_favourites_no_available)
                        return@launch
                    }
                    val checked = BooleanArray(candidates.size) { true }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle(org.skepsun.kototoro.R.string.sync_favourites_title)
                        .setMessage(org.skepsun.kototoro.R.string.sync_favourites_warning)
                        .setMultiChoiceItems(candidates.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                            checked[which] = isChecked
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            viewModel.syncFavorites(candidates.filterIndexed { index, _ -> checked[index] })
                        }
                        .show()
                }
            }

            DisposableEffect(appRouter, viewModel) {
                onContextualMenuActionsChanged(
                    listOf(
                        KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.favourites_categories) {
                            appRouter.openFavoriteCategories()
                        },
                        KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.batch_source_migration) {
                            viewModel.showMigrationPanel()
                        },
                        KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.import_favourites) {
                            showImportDialog(coroutineScope)
                        },
                        KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.sync_favourites) {
                            showSyncDialog(coroutineScope)
                        },
                    ),
                )
                onDispose {
                    onContextualMenuActionsChanged(emptyList())
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

            DisposableEffect(mainActivity, viewModel, selectedGroupTab, selectedSourceTags) {
                val callback = object : SearchBarFilterViewController.Callback {
                    override fun isSourceTagFilterVisible(): Boolean = true

                    override fun getSourceTagEntries(): List<org.skepsun.kototoro.explore.ui.model.SourceTag> =
                        org.skepsun.kototoro.explore.ui.model.SourceTag.quickFilterEntries

                    override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

                    override fun onContentTypeSelected(tab: BrowseGroupTab) {
                        viewModel.globalFavoritesState.setSelectedGroupTab(
                            if (selectedGroupTab == tab) BrowseGroupTab.All else tab,
                        )
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

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                KototoroFavoritesHostRoute(
                    appRouter = appRouter,
                    contentPadding = contentPadding,
                    onNavigateToDetails = navigateToDetailsWithContent,
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
        }
        composable<ExploreRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val exploreViewModel = hiltViewModel<org.skepsun.kototoro.explore.ui.ExploreViewModel>()
            val selectedGroupTab by exploreViewModel.currentGroupTab.collectAsStateWithLifecycle()
            val selectedSourceTags by exploreViewModel.currentSourceTags.collectAsStateWithLifecycle()

            DisposableEffect(mainActivity, exploreViewModel, selectedGroupTab, selectedSourceTags) {
                val callback = object : SearchBarFilterViewController.Callback {
                    override fun getSelectedContentType(): BrowseGroupTab = selectedGroupTab

                    override fun onContentTypeSelected(tab: BrowseGroupTab) {
                        exploreViewModel.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
                    }

                    override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> = selectedSourceTags

                    override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                        exploreViewModel.setSelectedSourceTags(
                            when {
                                tag == null -> emptySet()
                                tag in selectedSourceTags -> selectedSourceTags - tag
                                else -> selectedSourceTags + tag
                            }
                        )
                    }
                }
                mainActivity?.setActiveFilterCallback(callback)
                onDispose {
                    mainActivity?.clearActiveFilterCallback(callback)
                }
            }

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                org.skepsun.kototoro.explore.ui.compose.KototoroExploreHostRoute(
                    appRouter = appRouter,
                    contentPadding = contentPadding,
                    exploreViewModel = exploreViewModel,
                    onSourceSelectionTopBarChanged = {
                        onExploreSourceSelectionTopBarChanged(
                            RouteScopedTopBarOverrideState(TOP_BAR_OWNER_EXPLORE, it),
                        )
                    },
                    onNavigateToDetails = navigateToDetailsWithOrigin,
                )
            }
            }
        }
        composable<FeedRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.tracker.ui.feed.FeedViewModel>()
            val context = LocalContext.current
            val items by viewModel.content.collectAsStateWithLifecycle()
            val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
            val categories by viewModel.categories.collectAsStateWithLifecycle()
            val selectedCategoryId by viewModel.currentCategoryId.collectAsStateWithLifecycle()
            val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
            val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()

            val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.activity.ComponentActivity
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

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
                            }
                        )
                    }
                }
                mainActivity?.setActiveFilterCallback(callback)
                onDispose {
                    mainActivity?.clearActiveFilterCallback(callback)
                }
            }

            androidx.compose.runtime.DisposableEffect(viewModel, activity, lifecycleOwner) {
                val menuProvider = org.skepsun.kototoro.tracker.ui.feed.FeedMenuProvider(
                    snackbarHost = activity?.window?.decorView?.rootView ?: android.view.View(activity),
                    viewModel = viewModel
                )
                activity?.addMenuProvider(menuProvider, lifecycleOwner, androidx.lifecycle.Lifecycle.State.RESUMED)
                onDispose {
                    activity?.removeMenuProvider(menuProvider)
                }
            }

            androidx.compose.runtime.LaunchedEffect(viewModel.onError) {
                val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
                val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseActivity<*>)?.exceptionResolver
                val observer = org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver(host, null, resolver) { resolved ->
                    if (resolved) viewModel.update()
                }
                viewModel.onError.collect { event: org.skepsun.kototoro.core.util.Event<Throwable>? ->
                    event?.consume(observer)
                }
            }

            val feedCategoryTabsState = remember(categories, selectedCategoryId) {
                CompactTabsTopBarOverrideState(
                    items = categories.map { category ->
                        CompactTopBarTabItem(
                            id = category.id,
                            title = if (category.id == NO_ID) {
                                context.getString(org.skepsun.kototoro.R.string.all_favourites)
                            } else {
                                category.title
                            },
                        )
                    },
                    selectedItemId = selectedCategoryId,
                    onItemSelected = { categoryId ->
                        viewModel.selectCategory(if (selectedCategoryId == categoryId) NO_ID else categoryId)
                    },
                )
            }

            SideEffect {
                onExploreSourceSelectionTopBarChanged(
                    RouteScopedTopBarOverrideState(
                        TOP_BAR_OWNER_FEED,
                        LayeredTopBarOverrideState(
                            tabsState = feedCategoryTabsState,
                        ),
                    ),
                )
            }

            DisposableEffect(Unit) {
                onDispose {
                    onExploreSourceSelectionTopBarChanged(RouteScopedTopBarOverrideState(TOP_BAR_OWNER_FEED, null))
                }
            }

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                org.skepsun.kototoro.tracker.ui.feed.compose.FeedScreen(
                    contentPadding = contentPadding,
                    items = items,
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.update() },
                    onLoadMore = { viewModel.requestMoreItems() },
                    onFeedItemClick = { item, coverBounds ->
                        viewModel.onItemClick(item)
                        val content = item.toContentWithOverride()
                        navigateToDetailsWithContent(
                            content,
                            contentCoverSharedKey(
                                item.manga.source.name,
                                item.imageUrl.orEmpty(),
                                instanceKey = "feed_${item.id}",
                            ),
                        )
                    },
                    onUpdatedContentItemClick = { contentItem, coverBounds ->
                        val content = contentItem.toContentWithOverride()
                        navigateToDetailsWithContent(
                            content,
                            contentCoverSharedKey(
                                contentItem.manga.source.name,
                                contentItem.coverUrl.orEmpty(),
                                instanceKey = "feed_updated_${contentItem.id}",
                            ),
                        )
                    },
                    onUpdatedContentMoreClick = {
                        navController.navigate(UpdatedRoute) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    onCategorySelected = viewModel::selectCategory,
                    showCategoryFilterInline = false,
                )
            }
            }
        }
        composable<LocalRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.local.ui.LocalListViewModel>()
            val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.activity.ComponentActivity
            val availableTags by viewModel.filterAvailableTags.collectAsStateWithLifecycle(initialValue = emptySet())
            val selectedTagKeys by viewModel.filterSelectedTagKeys.collectAsStateWithLifecycle(initialValue = emptySet())
            DisposableEffect(appRouter) {
                onContextualMenuActionsChanged(
                    buildList {
                        add(
                            KototoroTopBarMenuAction(org.skepsun.kototoro.R.string._import) {
                                appRouter.showImportDialog()
                            },
                        )
                        if (appRouter.isFilterSupported()) {
                            add(
                                KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.filter) {
                                    appRouter.showFilterSheet()
                                },
                            )
                        }
                        add(
                            KototoroTopBarMenuAction(org.skepsun.kototoro.R.string.directories) {
                                appRouter.openDirectoriesSettings()
                            },
                        )
                    },
                )
                onDispose {
                    onContextualMenuActionsChanged(emptyList())
                }
            }
            val localFilterRailState = remember(availableTags, selectedTagKeys) {
                val items = availableTags.map { tag ->
                    CompactFilterRailItem(
                        id = "local_tag_${tag.key}",
                        title = tag.title,
                        isSelected = tag.key in selectedTagKeys,
                        onClick = { viewModel.toggleFilterTag(tag) },
                    )
                }.selectedFirst()
                CompactFilterRailOverrideState(items = items)
            }
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                org.skepsun.kototoro.list.ui.compose.AppContentListRoute(
                    viewModel = viewModel,
                    contentPadding = contentPadding,
                    appRouter = appRouter,
                    pullRefreshEnabled = false,
                    onTopBarOverrideChanged = {
                        onExploreSourceSelectionTopBarChanged(
                            RouteScopedTopBarOverrideState(
                                TOP_BAR_OWNER_LOCAL,
                                LayeredTopBarOverrideState(
                                    filterRailState = localFilterRailState,
                                    contextualOverrideState = it,
                                ),
                            ),
                        )
                    },
                    showRemoveOption = true,
                    sharedElementInstanceKey = "main_local",
                    isContentTypeFilterVisible = true,
                    onNavigateToDetails = navigateToDetailsWithContent,
                    isSourceTagFilterVisible = false,
                    onRemoveSelection = { ids ->
                        if (activity != null) {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                .setTitle(org.skepsun.kototoro.R.string.delete_manga)
                                .setMessage(org.skepsun.kototoro.R.string.text_delete_local_manga_batch)
                                .setPositiveButton(org.skepsun.kototoro.R.string.delete) { _, _ -> viewModel.delete(ids) }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    },
                    onShareSelection = { ids ->
                        if (activity != null) {
                            val files = viewModel.content.value.filter { it is org.skepsun.kototoro.list.ui.model.ContentListModel && it.id in ids }.mapNotNull { (it as? org.skepsun.kototoro.list.ui.model.ContentListModel)?.manga?.url?.let { url -> java.io.File(android.net.Uri.parse(url).path ?: "") } }
                            org.skepsun.kototoro.core.util.ShareHelper(activity).shareCbz(files)
                        }
                    },
                    onEmptyActionClick = { appRouter.showImportDialog() },
                    listHeader = null,
                )
            }
            }
        }
        composable<SuggestionsRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.suggestions.ui.SuggestionsViewModel>()
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

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
                org.skepsun.kototoro.list.ui.compose.AppContentListRoute(
                    viewModel = viewModel,
                    contentPadding = contentPadding,
                    appRouter = appRouter,
                    onTopBarOverrideChanged = { suggestionsContextualTopBarOverride = it },
                    showRemoveOption = false,
                    sharedElementInstanceKey = "main_suggestions",
                    isContentTypeFilterVisible = true,
                    isSourceTagFilterVisible = true,
                    onNavigateToDetails = navigateToDetailsWithContent,
                    onFilterRailOverrideChanged = { suggestionsFilterRailOverride = it },
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
                                    com.google.android.material.snackbar.Snackbar.make(act.window.decorView.rootView, org.skepsun.kototoro.R.string.suggestions_updating, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
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
                    }
                )
            }
            }
        }
        composable<BookmarksRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.bookmarks.ui.AllBookmarksViewModel>()
            val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
            val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()

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

            org.skepsun.kototoro.bookmarks.ui.compose.AppBookmarksRoute(
                viewModel = viewModel,
                contentPadding = contentPadding,
                appRouter = appRouter,
                pageSaveHelper = requireNotNull(pageSaveHelper) {
                    "BookmarksRoute requires a pre-registered PageSaveHelper"
                },
            )
            }
        }
        composable<UpdatedRoute> {
            MainRouteScene(landscapeStartPadding = landscapeStartPadding) {
            val viewModel = hiltViewModel<org.skepsun.kototoro.tracker.ui.updates.UpdatesViewModel>()
            val items by viewModel.content.collectAsStateWithLifecycle()
            val updatedCategoryTabsState = remember(items, activity) {
                buildFavoriteCategoryTabsState(
                    items = items,
                    allTitle = activity.getString(org.skepsun.kototoro.R.string.all_favourites),
                    onClearSelection = { viewModel.clearFilter() },
                    onCategorySelected = { option ->
                        viewModel.clearFilter()
                        viewModel.setFilterOption(option, true)
                    },
                )
            }
            var updatedContextualTopBarOverride by remember { mutableStateOf<TopBarOverrideState?>(null) }

            SideEffect {
                onExploreSourceSelectionTopBarChanged(
                    RouteScopedTopBarOverrideState(
                        TOP_BAR_OWNER_UPDATED,
                        LayeredTopBarOverrideState(
                            tabsState = updatedCategoryTabsState,
                            contextualOverrideState = updatedContextualTopBarOverride,
                        ),
                    ),
                )
            }

            DisposableEffect(Unit) {
                onDispose {
                    onExploreSourceSelectionTopBarChanged(RouteScopedTopBarOverrideState(TOP_BAR_OWNER_UPDATED, null))
                }
            }

            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
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
                    onNavigateToDetails = navigateToDetailsWithContent,
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
                    showQuickFilterInline = false,
                )
            }
            }
        }
        composable<SearchRoute> {
            val viewModel = hiltViewModel<org.skepsun.kototoro.search.ui.multi.SearchViewModel>()
            SearchResultsRoute(
                viewModel = viewModel,
                onBackClick = { navController.navigateUp() },
                onOpenContent = { content ->
                    navigateToDetailsWithContent(content, null)
                },
                onPickContent = { },
                onOpenSourceResults = { item ->
                    if (item.listFilter == null) {
                        appRouter.openSearch(item.source, viewModel.query)
                    } else {
                        appRouter.openList(item.source, item.listFilter, item.sortOrder)
                    }
                },
                onManageLanguagePresets = appRouter::openSourcePresets,
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
        ) {
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
                    navController.popBackStack()
                }
                DetailsScreen(
                    viewModel = detailsViewModel,
                    pagesViewModel = pagesViewModel,
                    bookmarksViewModel = bookmarksViewModel,
                    settings = entryPoint.settings(),
                    appRouter = appRouter,
                    pageSaveHelper = effectivePageSaveHelper,
                    onBackClick = {
                        navController.popBackStack()
                    },
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
                            onFinish = { navController.popBackStack() },
                        )
                    },
                )
            }
        }
    }
}
