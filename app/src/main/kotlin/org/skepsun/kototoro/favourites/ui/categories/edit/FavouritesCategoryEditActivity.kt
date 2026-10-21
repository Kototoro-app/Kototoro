package org.skepsun.kototoro.favourites.ui.categories.edit

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getSerializableCompat
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.favourites.ui.categories.edit.compose.FavouritesCategoryEditScreen
import org.skepsun.kototoro.list.domain.ListSortOrder

@AndroidEntryPoint
class FavouritesCategoryEditActivity :
    BaseComposeActivity(),
    ActivityResultCallback<Uri?> {

    private val viewModel by viewModels<FavouritesCategoryEditViewModel>()
    private var selectedSortOrder: ListSortOrder? = null
    private var title by mutableStateOf("")
    private var isTrackerEnabled by mutableStateOf(false)
    private var isVisibleOnShelf by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)

    private val pickCoverLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument(), this)

    override fun onActivityResult(result: Uri?) {
        if (result != null) {
            viewModel.setPickedCover(result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onSaved.observeEvent(this) { finishAfterTransition() }
        viewModel.onCoverRejected.observeEvent(this) {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar(getString(R.string.custom_cover_failed))
            }
        }
        viewModel.onError.observeEvent(this) {
            errorMessage = it.getDisplayMessage(resources)
        }
        setComposeContent {
            val category = viewModel.category.collectAsStateWithLifecycle().value
            val isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value
            val trackerAvailable = viewModel.isTrackerEnabled.collectAsStateWithLifecycle().value
            val coverPreview = viewModel.coverPreview.collectAsStateWithLifecycle().value
            val canRemoveCover = viewModel.canRemoveCover.collectAsStateWithLifecycle().value
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
                coverModel = coverPreview,
                canRemoveCover = canRemoveCover,
                onPickCover = {
                    if (!pickCoverLauncher.tryLaunch(arrayOf("image/*"))) {
                        lifecycleScope.launch {
                            snackbarHostState.showSnackbar(getString(R.string.operation_not_supported))
                        }
                    }
                },
                onRemoveCover = viewModel::removeCover,
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
