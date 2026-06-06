package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.favourites.ui.list.FavouritesListViewModel
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.Content

private const val FAVORITES_LOAD_MORE_VISIBLE_THRESHOLD = 48

@Composable
fun KototoroFavoritesListScreen(
    categoryId: Long,
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    onNavigateToDetails: ((ContentListModel, Content, String?) -> Unit)? = null,
    onEntityOrganizeSelection: ((Set<Long>) -> Unit)? = null,
    sharedTransitionEnabled: Boolean = true,
    isActivePage: Boolean = true,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    onFilterRailOverrideChanged: (CompactFilterRailOverrideState?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<FavouritesListViewModel, FavouritesListViewModel.Factory>(
        key = categoryId.toString(),
    ) { factory ->
        factory.create(categoryId)
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
        onNavigateToDetails = { item, content, sharedKey ->
            val entityId = viewModel.resolveEntityIdForUiItemId(item.id)
            if (entityId != null) {
                appRouter.openEntityDetails(
                    entityId = entityId,
                    preferredLocalMangaId = viewModel.resolvePreferredLocalMangaIdForUiItemId(item.id) ?: content.id,
                )
            } else if (onNavigateToDetails != null) {
                onNavigateToDetails(item, content, sharedKey)
            } else {
                appRouter.openDetails(content)
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
