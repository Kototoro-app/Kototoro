package org.skepsun.kototoro.home.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.home.ui.compose.HomeScreen
import org.skepsun.kototoro.home.ui.compose.HomeScreenActions
import org.skepsun.kototoro.main.ui.LocalMainChromeController
import org.skepsun.kototoro.main.ui.SearchBarFilterCallback
import org.skepsun.kototoro.main.ui.navigation3.BookmarksNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey
import org.skepsun.kototoro.main.ui.navigation3.LocalNavKey
import org.skepsun.kototoro.main.ui.navigation3.MainNavigator
import org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey
import org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.space.ui.spaceBoundHiltViewModel

@Composable
internal fun HomeRoute(
    animatedVisibilityScope: AnimatedVisibilityScope,
    appRouter: AppRouter,
    rootView: View,
    contentPadding: PaddingValues,
    mainNavigator: MainNavigator,
    onOpenSearch: (SearchNavigationRequest) -> Unit,
    navigateToDetailsWithContent: (Content, String?) -> Unit,
    isRouteVisible: Boolean = true,
) {
    val viewModel = spaceBoundHiltViewModel<HomeViewModel>("home")
    val mainChromeController = LocalMainChromeController.current
    val state by viewModel.summaryState.collectAsStateWithLifecycle()
    val isRandomLoading by viewModel.isRandomLoading.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    LaunchedEffect(viewModel.onOpenContent, navigateToDetailsWithContent) {
        viewModel.onOpenContent.collect { event ->
            event?.consume { contentEvent ->
                navigateToDetailsWithContent(contentEvent.content, null)
            }
        }
    }

    LaunchedEffect(viewModel.onActionDone) {
        val observer = ReversibleActionObserver(rootView)
        viewModel.onActionDone.collect { event ->
            event?.consume(observer)
        }
    }

    LaunchedEffect(viewModel.onError, activity) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val resolver = (activity as? BaseComposeActivity)?.exceptionResolver
        val observer = SnackbarErrorObserver(host, resolver, null)
        viewModel.onError.collect { event ->
            event?.consume(observer)
        }
    }

    DisposableEffect(mainChromeController, viewModel, state.selectedTab, state.selectedSourceTags) {
        val callback = object : SearchBarFilterCallback {
            override fun getSelectedContentType(): BrowseGroupTab = when (state.selectedTab) {
                HomeContentTab.MANGA -> BrowseGroupTab.Content
                HomeContentTab.NOVEL -> BrowseGroupTab.Novel
                HomeContentTab.VIDEO -> BrowseGroupTab.Video
                null -> BrowseGroupTab.All
            }

            override fun onContentTypeSelected(tab: BrowseGroupTab) {
                viewModel.setSelectedTab(
                    when (if (getSelectedContentType() == tab) BrowseGroupTab.All else tab) {
                        BrowseGroupTab.Content -> HomeContentTab.MANGA
                        BrowseGroupTab.Novel -> HomeContentTab.NOVEL
                        BrowseGroupTab.Video -> HomeContentTab.VIDEO
                        else -> null
                    },
                )
            }

            override fun getSelectedSourceTags(): Set<SourceTag> =
                state.selectedSourceTags

            override fun onSourceTagSelected(tag: SourceTag?) {
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
        mainChromeController?.setActiveFilterCallback(callback)
        onDispose {
            mainChromeController?.clearActiveFilterCallback(callback)
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
                        kind = SearchKind.SIMPLE,
                        sourceTypes = ALL_SOURCE_TYPES,
                        contentKinds = ALL_SEARCH_CONTENT_KINDS,
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
