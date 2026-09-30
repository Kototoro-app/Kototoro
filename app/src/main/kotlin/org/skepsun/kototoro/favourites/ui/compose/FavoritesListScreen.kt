package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.core.ui.compose.ReorderDialog
import org.skepsun.kototoro.core.ui.compose.ReorderItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.favourites.ui.list.FavouritesListHost
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute
import org.skepsun.kototoro.list.ui.compose.QuickFilterSection
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
    val shelf by listHost.shelf.collectAsStateWithLifecycle()
    val gridScale by listHost.gridScale.collectAsStateWithLifecycle()
    val listMode by listHost.listMode.collectAsStateWithLifecycle()
    val shelfInstanceKey = "main_favorites_shelf_$categoryId"
    var orderItems by remember(categoryId) { mutableStateOf<List<ReorderItem>?>(null) }
    orderItems?.let { items ->
        ReorderDialog(items, onDismissRequest = { orderItems = null }, onSave = listHost::saveOrder)
    }
    val canReorder = categoryId != org.skepsun.kototoro.core.model.FavouriteCategory.NO_ID
    val sortControl: (@Composable () -> Unit)? = if (sortOrders.isNotEmpty()) {
        {
            SortOrderControl(
                sortOrders = sortOrders,
                selectedSortOrder = selectedSortOrder,
                onSortOrderSelected = onSortOrderSelected,
                compact = true,
                onReorderClick = if (canReorder) { { orderItems = listHost.orderItems() } } else null,
            )
        }
    } else {
        null
    }

    AppContentListRoute(
        viewModel = listHost,
        contentPadding = contentPadding,
        appRouter = appRouter,
        // The quick filters narrow the shelf as well as the grid, so they sit above both
        // (right under the category tabs) instead of being rendered as the first list item,
        // which put them between the shelf and the grid.
        listHeader = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (quickFilter != null) {
                    QuickFilterSection(
                        quickFilter = quickFilter!!,
                        onQuickFilterOptionClick = listHost::toggleFilterOption,
                        leadingContent = sortControl,
                    )
                } else if (sortControl != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sortControl()
                    }
                }
                FavoritesShelf(
                    state = shelf,
                    gridScale = gridScale,
                    compactOverlay = listMode == ListMode.COMPACT_GRID,
                    sharedElementInstanceKey = shelfInstanceKey,
                    onItemClick = { item, _, sharedKey ->
                        if (onNavigateToDetails != null) {
                            onNavigateToDetails(item.manga, sharedKey)
                        } else {
                            appRouter.openResolvedDetails(item.manga, sharedElementKey = sharedKey)
                        }
                    },
                )
            }
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
        showQuickFilterInline = false,
        enableItemAnimations = false,
    )
}
