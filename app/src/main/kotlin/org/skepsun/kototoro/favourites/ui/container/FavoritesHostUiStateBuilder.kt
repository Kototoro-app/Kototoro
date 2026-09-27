package org.skepsun.kototoro.favourites.ui.container

import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel.FavoritesHostUiState

/**
 * Tabs and empty state of the favourites host, from the user's categories and the per-slice
 * counts of the derived library.
 *
 * A group tab (the browse space) or source tags hide the categories holding no matching
 * work. Quick filters are different: they narrow what the user already scoped to, so when
 * they hide everything the tabs stay and the category page shows its quick-filter row and
 * "nothing found / reset filter" state. Otherwise the Downloaded filter that follows the
 * offline state turned a full library into "No favourites yet" with no way to undo it.
 */
internal fun buildFavoritesHostUiState(
    categories: List<FavouriteTabModel>,
    categoryCounts: Map<Long, Int>,
    groupTab: BrowseGroupTab,
    hasSourceTags: Boolean,
    hasQuickFilters: Boolean,
    showAll: Boolean,
): FavoritesHostUiState {
    val hasActiveFilter = groupTab != BrowseGroupTab.All || hasSourceTags
    val nothingVisible = categoryCounts.values.all { it == 0 }
    val keepAllTabs = !hasActiveFilter || (hasQuickFilters && nothingVisible)
    val visibleCategories = if (keepAllTabs) {
        categories
    } else {
        categories.filter { categoryCounts.getOrDefault(it.id, 0) > 0 }
    }
    val result = ArrayList<FavouriteTabModel>(visibleCategories.size + 1)
    if (showAll && (keepAllTabs || categoryCounts.getOrDefault(NO_ID, 0) > 0)) {
        result.add(FavouriteTabModel(NO_ID, null))
    }
    result.addAll(visibleCategories)
    return FavoritesHostUiState(
        isLoading = false,
        categories = result,
        isEmpty = result.isEmpty(),
    )
}
