package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.favourites.ui.list.FavouritesListViewModel
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.space.ui.LocalBrowseSpaceId
import org.skepsun.kototoro.space.ui.spaceViewModelKey

private const val FAVORITES_LOAD_MORE_VISIBLE_THRESHOLD = 48

@Composable
fun KototoroFavoritesListScreen(
    categoryId: Long,
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
    val spaceId = LocalBrowseSpaceId.current
    val viewModel = hiltViewModel<FavouritesListViewModel, FavouritesListViewModel.Factory>(
        key = spaceViewModelKey("favorites-$categoryId", spaceId),
    ) { factory ->
        factory.create(categoryId)
    }
    LaunchedEffect(viewModel, spaceId) {
        viewModel.bindSpace(spaceId)
    }

    AppContentListRoute(
        viewModel = viewModel,
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
        pullRefreshEnabled = false,
        onLoadMore = { viewModel.requestMoreItems() },
        loadMoreVisibleThreshold = FAVORITES_LOAD_MORE_VISIBLE_THRESHOLD,
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
            val origin = DetailsOrigin.EntityGraph(
                entityId = entityId,
                preferredLocalMangaId = preferredLocalMangaId ?: content.id,
            )
            if (onNavigateToEntityDetails != null) {
                onNavigateToEntityDetails(origin, sharedKey)
            } else {
                appRouter.openEntityDetails(
                    entityId = entityId,
                    preferredLocalMangaId = preferredLocalMangaId ?: content.id,
                    sharedElementKey = sharedKey,
                )
            }
        },
        onRemoveSelection = { ids -> viewModel.removeFromFavourites(ids) },
        onPinSelection = { ids -> viewModel.togglePinned(ids) },
        onMarkAsCompletedSelection = { items -> viewModel.markAsRead(items.map { it.manga }.toSet()) },
        onFixSelection = { ids ->
            onEntityOrganizeSelection?.invoke(viewModel.resolveSelectionToMangaIds(ids))
        },
        fixSelectionActionTitleRes = R.string.entity_organize_title,
        showQuickFilterInline = true,
        enableItemAnimations = false,
    )
}
