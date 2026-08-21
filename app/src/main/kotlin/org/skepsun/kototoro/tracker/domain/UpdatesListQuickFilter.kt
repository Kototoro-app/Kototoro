package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import javax.inject.Inject

class UpdatesListQuickFilter @Inject constructor(
    private val favouritesRepository: FavouritesRepository,
    settings: AppSettings,
) : ContentListQuickFilter(settings) {

    override suspend fun getAvailableFilterOptions(): List<ListFilterOption> =
        favouritesRepository.getMostUpdatedCategories(
            limit = 4,
        ).map {
            ListFilterOption.Favorite(it)
        }
}
