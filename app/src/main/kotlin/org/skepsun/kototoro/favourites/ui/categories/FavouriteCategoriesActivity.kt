package org.skepsun.kototoro.favourites.ui.categories

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.favourites.ui.categories.compose.FavouriteCategoriesScreen

@AndroidEntryPoint
class FavouriteCategoriesActivity :
	BaseComposeActivity() {

	private val viewModel by viewModels<FavouritesCategoriesViewModel>()
	private var selectedIds by mutableStateOf<Set<Long>>(emptySet())

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(window.decorView, null, exceptionResolver, null))
		setComposeContent {
			FavouriteCategoriesScreen(
				items = viewModel.content.collectAsStateWithLifecycle().value,
				selectedIds = selectedIds,
				onSelectionChanged = { selectedIds = it },
				onAdd = { router.openFavoriteCategoryCreate() },
				onOpenAll = { router.openFavorites() },
				onOpenCategory = { router.openFavorites(it) },
				onEditCategory = { router.openFavoriteCategoryEdit(it.id) },
				onShowAllChanged = viewModel::setAllCategoriesVisible,
				onSetVisible = viewModel::setIsVisible,
				onDelete = viewModel::deleteCategories,
				onSaveOrder = viewModel::saveOrder,
			)
		}
	}
}
