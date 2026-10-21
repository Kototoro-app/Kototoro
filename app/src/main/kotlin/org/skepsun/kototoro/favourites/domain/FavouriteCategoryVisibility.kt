package org.skepsun.kototoro.favourites.domain

import org.skepsun.kototoro.core.model.FavouriteCategory

/**
 * Categories a Space should show, for both the favourites tab strip and the
 * "add to favourites" dialog.
 *
 * Rules, in order:
 *  - an unconfigured Space (empty [hiddenIds]) shows everything;
 *  - [keepIds] never disappears: a category the user is looking at, or one the content is
 *    already filed under, must stay reachable so it can be opened or unchecked;
 *  - a Space never hides its own last category — an empty strip would look like an empty
 *    library, so the hidden set falls back to "show all" when it would hide everything.
 */
internal fun filterCategoriesForSpace(
    categories: List<FavouriteCategory>,
    hiddenIds: Set<Long>,
    keepIds: Set<Long> = emptySet(),
): List<FavouriteCategory> {
    if (hiddenIds.isEmpty()) {
        return categories
    }
    val filtered = categories.filter { it.id !in hiddenIds || it.id in keepIds }
    return filtered.ifEmpty { categories }
}
