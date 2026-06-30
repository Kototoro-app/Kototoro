package org.skepsun.kototoro.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import org.skepsun.kototoro.core.model.isNsfw

class FavoritesListQuickFilter @AssistedInject constructor(
	@Assisted private val categoryId: Long,
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
	networkState: NetworkState,
	private val globalFilterState: GlobalFavoritesState,
) : ContentListQuickFilter(settings) {

	init {
		// Sync initial state if needed, or rely on global state.
		// Note: ContentListQuickFilter sets 'Downloaded' based on network in init.
		// We might want to apply that to global state ONLY if it's the first init?
		// Or just let user control.
		// For now, let's keep the network logic but apply it to global state
		globalFilterState.setFilterOption(ListFilterOption.Downloaded, !networkState.value)
	}

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

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList {
		add(ListFilterOption.Downloaded)
		if (!settings.isFavouritesExcludeNsfw) {
			add(ListFilterOption.SFW)          // 全年龄
			add(ListFilterOption.Macro.NSFW)   // R18
		}
		if (settings.isTrackerEnabled) {
			add(ListFilterOption.Macro.NEW_CHAPTERS)
		}
		add(ListFilterOption.Macro.MULTI_PROJECTION)
		add(ListFilterOption.Macro.COMPLETED)
		val hideNsfw = settings.isFavouritesExcludeNsfw
		try {
			repository.findPopularTags(categoryId, 3)
				.mapTo(this) { ListFilterOption.Tag(it) }
            repository.findPopularSources(categoryId, Int.MAX_VALUE)
                .filterNot { hideNsfw && it.isNsfw() }
                .mapTo(this) { ListFilterOption.Source(it) }
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
		}
	}

	@AssistedFactory
	interface Factory {

		fun create(categoryId: Long): FavoritesListQuickFilter
	}
}
