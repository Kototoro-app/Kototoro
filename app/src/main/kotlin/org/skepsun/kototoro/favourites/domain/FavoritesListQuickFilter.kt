package org.skepsun.kototoro.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.favourites.domain.library.FavouritesQuickFilterInput
import org.skepsun.kototoro.favourites.domain.library.buildFavouritesFilterOptions
import org.skepsun.kototoro.favourites.ui.container.FavouriteLibraryUiState
import org.skepsun.kototoro.list.ui.model.QuickFilter

/**
 * Quick filters of one favourites category (favourites-komikku-alignment Phase 6).
 *
 * The chips are derived from the shared library snapshot
 * ([buildFavouritesFilterOptions]) instead of a per-category database query, so switching
 * a tab or toggling a filter never reads the database. Selection itself stays in
 * [GlobalFavoritesState] — the filter object is stateless apart from the base class.
 */
class FavoritesListQuickFilter @AssistedInject constructor(
    @Assisted private val categoryId: Long,
    @Assisted private val libraryState: StateFlow<FavouriteLibraryUiState>,
    private val settings: AppSettings,
    private val globalFilterState: GlobalFavoritesState,
) : ContentListQuickFilter(settings) {

    override val appliedOptions = globalFilterState.appliedFilter

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
        globalFilterState.setFilterOption(option, isApplied)
    }

    override fun toggleFilterOption(option: ListFilterOption) {
        globalFilterState.toggleFilterOption(option)
    }

    override fun clearFilter() {
        globalFilterState.clearFilter()
    }

    override fun createFilterModel(chips: List<ChipModel>): QuickFilter =
        buildFavoritesQuickFilter(chips)

    override suspend fun getAvailableFilterOptions(): List<ListFilterOption> {
        // The base class caches the first evaluation forever, and the library snapshot
        // starts empty (isInitialized = false) before its first complete emission. The
        // all-favourites host is the first pager page, so it used to evaluate against the
        // empty snapshot and permanently cache the macro-only chip set (tags/sources
        // missing) while later-created category tabs got the full set. Wait for the first
        // complete snapshot before computing.
        val library = libraryState.first { it.isInitialized }
        return buildFavouritesFilterOptions(
            FavouritesQuickFilterInput(
                categoryId = categoryId,
                membershipsByCategory = library.membershipsByCategory,
                allEntityIds = library.allEntityIds,
                rows = library.rowsByEntityId,
                metadata = library.quickFilterMetadata,
                excludeNsfw = settings.isFavouritesExcludeNsfw,
                isTrackerEnabled = settings.isTrackerEnabled,
            ),
        )
    }

    @AssistedFactory
    interface Factory {

        fun create(
            categoryId: Long,
            libraryState: StateFlow<FavouriteLibraryUiState>,
        ): FavoritesListQuickFilter
    }
}
