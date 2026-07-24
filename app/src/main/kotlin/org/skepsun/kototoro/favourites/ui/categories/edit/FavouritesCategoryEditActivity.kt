package org.skepsun.kototoro.favourites.ui.categories.edit

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getSerializableCompat
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.favourites.ui.categories.edit.compose.FavouritesCategoryEditScreen
import org.skepsun.kototoro.list.domain.ListSortOrder

@AndroidEntryPoint
class FavouritesCategoryEditActivity :
	BaseComposeActivity() {

	private val viewModel by viewModels<FavouritesCategoryEditViewModel>()
	private var selectedSortOrder: ListSortOrder? = null
	private var title by mutableStateOf("")
	private var isTrackerEnabled by mutableStateOf(false)
	private var isVisibleOnShelf by mutableStateOf(true)
	private var errorMessage by mutableStateOf<String?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel.onSaved.observeEvent(this) { finishAfterTransition() }
		viewModel.onError.observeEvent(this) {
			errorMessage = it.getDisplayMessage(resources)
		}
		setComposeContent {
			val category = viewModel.category.collectAsStateWithLifecycle().value
			val isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value
			val trackerAvailable = viewModel.isTrackerEnabled.collectAsStateWithLifecycle().value
			LaunchedEffect(category) {
				if (category != null && title.isEmpty()) {
					title = category.title
					selectedSortOrder = category.order
					isTrackerEnabled = category.isTrackingEnabled
					isVisibleOnShelf = category.isVisibleInLibrary
				}
			}
			LaunchedEffect(category) {
				if (selectedSortOrder == null) {
					title = category?.title.orEmpty()
					selectedSortOrder = category?.order
					isTrackerEnabled = category?.isTrackingEnabled != false
					isVisibleOnShelf = category?.isVisibleInLibrary != false
				}
			}
			LaunchedEffect(isLoading) {
				if (isLoading) {
					errorMessage = null
				}
			}
			FavouritesCategoryEditScreen(
				isEditing = category != null,
				title = title,
				sortOrder = selectedSortOrder ?: category?.order ?: ListSortOrder.NEWEST,
				isTrackerAvailable = trackerAvailable,
				isTrackerEnabled = isTrackerEnabled,
				isVisibleOnShelf = isVisibleOnShelf,
				isLoading = isLoading,
				errorMessage = errorMessage,
				onTitleChanged = {
					title = it.take(120)
					errorMessage = null
				},
				onSortOrderChanged = { selectedSortOrder = it },
				onTrackerChanged = { isTrackerEnabled = it },
				onShelfChanged = { isVisibleOnShelf = it },
				onSave = {
					errorMessage = null
					viewModel.save(title.trim(), selectedSortOrder ?: category?.order ?: ListSortOrder.NEWEST, isTrackerEnabled, isVisibleOnShelf)
				},
				onBack = ::finish,
			)
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putSerializable(KEY_SORT_ORDER, selectedSortOrder)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		savedInstanceState.getSerializableCompat<ListSortOrder>(KEY_SORT_ORDER)?.let {
			selectedSortOrder = it
		}
	}

	companion object {

		const val NO_ID = -1L
		private const val KEY_SORT_ORDER = "sort"
	}
}
