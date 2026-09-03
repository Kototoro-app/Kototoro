package org.skepsun.kototoro.tracker.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import org.skepsun.kototoro.tracker.domain.updates.UpdatesSnapshot
import javax.inject.Inject

class UpdatesListQuickFilter @Inject constructor(
    private val favouritesRepository: FavouritesRepository,
    settings: AppSettings,
) : ContentListQuickFilter(settings) {

    private val snapshot = CompletableDeferred<UpdatesSnapshot>()

    internal fun acceptSnapshot(value: UpdatesSnapshot) {
        snapshot.complete(value)
    }

    override suspend fun getAvailableFilterOptions(): List<ListFilterOption> =
        buildMostUpdatedCategories(
            snapshot = snapshot.await(),
            categories = favouritesRepository.observeCategoriesForLibrary().first(),
            limit = 4,
        ).map {
            ListFilterOption.Favorite(it)
        }
}

internal fun buildMostUpdatedCategories(
    snapshot: UpdatesSnapshot,
    categories: List<FavouriteCategory>,
    limit: Int,
): List<FavouriteCategory> {
    if (limit <= 0 || snapshot.isEmpty) {
        return emptyList()
    }
    val newChaptersByCategory = HashMap<Long, Int>(categories.size)
    for (group in snapshot.groups) {
        for (categoryId in group.categoryIds) {
            newChaptersByCategory[categoryId] =
                newChaptersByCategory.getOrDefault(categoryId, 0) + group.totalNewChapters
        }
    }
    return categories.asSequence()
        .filter { it.isTrackingEnabled && it.isVisibleInLibrary }
        .map { category -> category to newChaptersByCategory.getOrDefault(category.id, 0) }
        .filter { (_, newChapters) -> newChapters > 0 }
        .sortedByDescending { (_, newChapters) -> newChapters }
        .take(limit)
        .map { (category, _) -> category }
        .toList()
}
