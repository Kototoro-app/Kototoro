package org.skepsun.kototoro.settings.tracker.categories

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import javax.inject.Inject

@HiltViewModel
class TrackerCategoriesConfigViewModel @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val content = favouritesRepository.observeCategories()
		.map { categories -> TrackerCategoriesUiState(isLoading = false, categories = categories) }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, TrackerCategoriesUiState())

	private var updateJob: Job? = null

	fun toggleItem(category: FavouriteCategory) {
		val prevJob = updateJob
		updateJob = launchJob(Dispatchers.Default) {
			prevJob?.join()
			favouritesRepository.updateCategoryTracking(category.id, !category.isTrackingEnabled)
		}
	}
}

data class TrackerCategoriesUiState(
	val isLoading: Boolean = true,
	val categories: List<FavouriteCategory> = emptyList(),
)
