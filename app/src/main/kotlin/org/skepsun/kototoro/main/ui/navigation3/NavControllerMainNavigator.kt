package org.skepsun.kototoro.main.ui.navigation3

import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import org.skepsun.kototoro.core.nav.PendingContentListNavigation
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.compose.ContentListRoute
import org.skepsun.kototoro.main.ui.compose.DetailsRoute
import org.skepsun.kototoro.main.ui.compose.MainShellRoute
import org.skepsun.kototoro.main.ui.compose.routeForTopLevelKey
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.search.ui.compose.SearchNavigation
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.compose.SearchRoute

class NavControllerMainNavigator(
    private val navController: NavHostController,
    private val mainActivity: MainActivity?,
    private val mainNavState: MainNavState? = null,
    private val onDetailsTransitionRequested: () -> Unit = {},
) : MainNavigator {

    override fun openTopLevel(key: TopLevelNavKey) {
        mainNavState?.navigateTopLevel(key)
        if (navController.currentDestination?.hasRoute<MainShellRoute>() == true) {
            return
        }
        navController.navigate(routeForTopLevelKey(key)) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = false
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    override fun openContentList(
        source: ContentSource,
        filter: ContentListFilter?,
        sortOrder: SortOrder?,
    ) {
        onDetailsTransitionRequested()
        mainNavState?.push(ContentListNavKey(sourceName = source.name))
        PendingContentListNavigation.set(filter = filter, sortOrder = sortOrder)
        navController.navigate(ContentListRoute(sourceName = source.name)) {
            launchSingleTop = true
        }
    }

    override fun openDetails(
        content: Content,
        sharedElementKey: String?,
    ) {
        onDetailsTransitionRequested()
        mainActivity?.resolveDetailsOriginForContent(content) { origin ->
            mainNavState?.push(origin.toDetailsNavKey())
            PendingDetailsNavigation.set(origin, sharedElementKey)
            navController.navigate(DetailsRoute)
        } ?: run {
            mainNavState?.push(DetailsNavKey(requestedProjectionId = content.id))
            PendingDetailsNavigation.set(content, sharedElementKey)
            navController.navigate(DetailsRoute)
        }
    }

    override fun openDetails(
        origin: DetailsOrigin,
        sharedElementKey: String?,
    ) {
        onDetailsTransitionRequested()
        mainNavState?.push(origin.toDetailsNavKey())
        PendingDetailsNavigation.set(origin, sharedElementKey)
        navController.navigate(DetailsRoute)
    }

    override fun openSearch(request: SearchNavigationRequest) {
        mainNavState?.pushOrReplaceCurrentTopSearch(
            SearchNavKey(
                query = request.query,
                kind = request.kind.name,
                sourceTypes = request.sourceTypes.joinToString(",") { it.name },
                contentKinds = request.contentKinds.joinToString(",") { it.name },
                advancedTitle = request.advancedQuery?.title.orEmpty(),
                advancedTags = request.advancedQuery?.tags.orEmpty(),
                advancedAuthor = request.advancedQuery?.author.orEmpty(),
                pinnedOnly = request.pinnedOnly,
                hideEmpty = request.hideEmpty,
            ),
        )
        val route = SearchNavigation.createRoute(request)
        if (navController.currentDestination?.hasRoute<SearchRoute>() == true) {
            navController.navigate(route) {
                popUpTo<SearchRoute> { inclusive = true }
                launchSingleTop = true
            }
        } else {
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    override fun pop(): Boolean {
        val popped = navController.popBackStack()
        if (popped) {
            mainNavState?.pop()
        }
        return popped
    }
}

private fun DetailsOrigin.toDetailsNavKey(): DetailsNavKey = when (this) {
    is DetailsOrigin.EntityGraph -> DetailsNavKey(
        entityId = entityId,
        requestedProjectionId = initialProjectionLocalMangaId ?: preferredLocalMangaId,
    )
    is DetailsOrigin.LocalMangaId -> DetailsNavKey(requestedProjectionId = mangaId)
    is DetailsOrigin.LocalMangaContent -> DetailsNavKey(requestedProjectionId = manga.id)
    is DetailsOrigin.TrackingEntity,
    is DetailsOrigin.TrackingItem,
    -> DetailsNavKey()
}
