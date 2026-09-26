package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.favourites.ui.list.FavouritesListHost
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.compose.SortOrderControl
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content

@Composable
fun KototoroFavoritesListScreen(
    categoryId: Long,
    listHost: FavouritesListHost,
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    onNavigateToDetails: ((Content, String?) -> Unit)? = null,
    onNavigateToDetailsOrigin: ((DetailsOrigin, String?) -> Unit)? = null,
    sharedTransitionEnabled: Boolean = true,
    isActivePage: Boolean = true,
    sortOrders: List<ListSortOrder> = emptyList(),
    selectedSortOrder: ListSortOrder? = null,
    onSortOrderSelected: (ListSortOrder) -> Unit = {},
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    onFilterRailOverrideChanged: (CompactFilterRailOverrideState?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The state holder is the favourites container, handed in as a per-category slice:
    // there is no page-level ViewModel and no space binding to do here (Phase 6).
    val quickFilter by listHost.topQuickFilter.collectAsStateWithLifecycle()
    val library by listHost.libraryState.collectAsStateWithLifecycle()
    val categoryRows = remember(library, categoryId) {
        val ids = library.visibleIdsByCategory[categoryId].orEmpty()
        val byId = library.rowsByEntityId
        ids.mapNotNull { byId[it] }
    }
    val pinnedIds = remember(library, categoryId) {
        library.pinnedIdsByCategory[categoryId].orEmpty()
    }
    val totalCount = categoryRows.size
    val updatedCount = remember(categoryRows) {
        categoryRows.count { it.newChapters > 0 }
    }
    val spotlightRows = remember(categoryRows, pinnedIds) {
        if (categoryRows.isEmpty()) return@remember emptyList<org.skepsun.kototoro.favourites.domain.library.FavouriteCardRow>()
        val pinned = categoryRows.filter { it.entityId in pinnedIds }
        val withUpdates = categoryRows.filter { it.entityId !in pinnedIds && it.newChapters > 0 }
        val recentlyRead = categoryRows.filter { it.entityId !in pinnedIds && it.newChapters <= 0 && (it.lastReadAt ?: 0L) > 0L }
            .sortedByDescending { it.lastReadAt }
        (pinned + withUpdates + recentlyRead).take(6)
    }

    AppContentListRoute(
        viewModel = listHost,
        contentPadding = contentPadding,
        appRouter = appRouter,
        listHeader = {
            FavoritesSpotlightHeader(
                totalCount = totalCount,
                updatedCount = updatedCount,
                spotlightRows = spotlightRows,
                onItemClick = { row ->
                    val mangaId = row.displayMangaId ?: row.entityId
                    if (onNavigateToDetailsOrigin != null) {
                        onNavigateToDetailsOrigin(DetailsOrigin.LocalMangaId(mangaId), "fav_spotlight_${row.entityId}")
                    } else {
                        appRouter.openDetails(mangaId)
                    }
                },
                onCheckForUpdates = { listHost.checkForUpdates() },
            )
        },
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
        showScrollbar = true,
        pullRefreshAction = { listHost.checkForUpdates() },
        onNavigateToDetails = { _, content, sharedKey ->
            if (onNavigateToDetails != null) {
                onNavigateToDetails(content, sharedKey)
            } else {
                appRouter.openResolvedDetails(content, sharedElementKey = sharedKey)
            }
        },
        onRemoveSelection = { ids -> listHost.removeFromFavourites(ids) },
        onPinSelection = { ids -> listHost.togglePinned(ids) },
        onMarkAsCompletedSelection = { items -> listHost.markAsRead(items.map { it.id }) },
        onResolveSelectionContents = { ids -> listHost.resolveSelectedContents(ids) },
        showQuickFilterInline = true,
        quickFilterLeadingContent = if (sortOrders.isNotEmpty()) {
            {
                SortOrderControl(
                    sortOrders = sortOrders,
                    selectedSortOrder = selectedSortOrder,
                    onSortOrderSelected = onSortOrderSelected,
                    compact = true,
                )
            }
        } else {
            null
        },
        quickFilterOverride = quickFilter,
        enableItemAnimations = false,
    )
}
