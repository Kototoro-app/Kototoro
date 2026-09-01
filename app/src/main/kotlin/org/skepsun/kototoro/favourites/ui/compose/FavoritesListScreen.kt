package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.favourites.ui.list.FavouritesListHost
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.parsers.model.Content

@Composable
fun KototoroFavoritesListScreen(
    categoryId: Long,
    listHost: FavouritesListHost,
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    onNavigateToDetails: ((Content, String?) -> Unit)? = null,
    onNavigateToEntityDetails: ((DetailsOrigin, String?) -> Unit)? = null,
    onEntityOrganizeSelection: ((Set<Long>) -> Unit)? = null,
    sharedTransitionEnabled: Boolean = true,
    isActivePage: Boolean = true,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    onFilterRailOverrideChanged: (CompactFilterRailOverrideState?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val mainActivity = LocalContext.current as? MainActivity
    // The state holder is the favourites container, handed in as a per-category slice:
    // there is no page-level ViewModel and no space binding to do here (Phase 6).
    val quickFilter by listHost.topQuickFilter.collectAsStateWithLifecycle()

    AppContentListRoute(
        viewModel = listHost,
        contentPadding = contentPadding,
        appRouter = appRouter,
        showRemoveOption = true,
        preferredSelectionInlineActions = listOf(
            SelectionAction.PIN,
            SelectionAction.REMOVE,
            SelectionAction.SAVE,
        ),
        removeSelectionActionIconRes = R.drawable.ic_heart_outline,
        removeSelectionActionTitleRes = R.string.remove_from_favourites,
        onTopBarOverrideChanged = onTopBarOverrideChanged,
        onFilterRailOverrideChanged = {},
        emitFilterRailOverride = false,
        sharedTransitionEnabled = sharedTransitionEnabled,
        sharedElementInstanceKey = "main_favorites_$categoryId",
        registerFilterCallback = false,
        pullRefreshEnabled = true,
        pullRefreshAction = { listHost.checkForUpdates() },
        onNavigateToDetails = { _, content, sharedKey ->
            if (onNavigateToDetails != null) {
                onNavigateToDetails(content, sharedKey)
            } else {
                mainActivity?.resolveDetailsOriginForContent(content) { origin ->
                    when (origin) {
                        is DetailsOrigin.EntityGraph -> {
                            appRouter.openEntityDetails(
                                entityId = origin.entityId,
                                initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                sharedElementKey = sharedKey,
                            )
                        }
                        else -> appRouter.openResolvedDetails(content, sharedElementKey = sharedKey)
                    }
                } ?: appRouter.openResolvedDetails(content, sharedElementKey = sharedKey)
            }
        },
        onNavigateToEntityDetails = { _, content, entityId, preferredLocalMangaId, sharedKey ->
            // Item ids are entity ids now, so only a real display projection may seed the page.
            val preferred = preferredLocalMangaId ?: content.id.takeIf { it != entityId }
            val origin = DetailsOrigin.EntityGraph(
                entityId = entityId,
                preferredLocalMangaId = preferred,
            )
            if (onNavigateToEntityDetails != null) {
                onNavigateToEntityDetails(origin, sharedKey)
            } else {
                appRouter.openEntityDetails(
                    entityId = entityId,
                    preferredLocalMangaId = preferred,
                    sharedElementKey = sharedKey,
                )
            }
        },
        onRemoveSelection = { ids -> listHost.removeFromFavourites(ids) },
        onPinSelection = { ids -> listHost.togglePinned(ids) },
        onMarkAsCompletedSelection = { items -> listHost.markAsRead(items.map { it.id }) },
        onResolveSelectionContents = { ids -> listHost.resolveSelectedContents(ids) },
        onFixSelection = { ids ->
            onEntityOrganizeSelection?.invoke(listHost.resolveSelectionToMangaIds(ids))
        },
        fixSelectionActionTitleRes = R.string.entity_organize_title,
        showQuickFilterInline = true,
        quickFilterOverride = quickFilter,
        enableItemAnimations = false,
    )
}
