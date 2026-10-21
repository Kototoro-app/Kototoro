package org.skepsun.kototoro.favourites.ui.categories.edit

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.favourites.data.FavouriteCategoryCoverStore
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.favourites.ui.categories.edit.FavouritesCategoryEditActivity.Companion.NO_ID
import org.skepsun.kototoro.list.domain.ListSortOrder
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FavouritesCategoryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FavouritesRepository,
    private val settings: AppSettings,
    private val coverStore: FavouriteCategoryCoverStore,
) : BaseViewModel() {

    private val categoryId = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID

    val onSaved = MutableEventFlow<Unit>()

    /** The picked document could not be read; the previous cover (if any) is untouched. */
    val onCoverRejected = MutableEventFlow<Unit>()

    val category = MutableStateFlow<FavouriteCategory?>(null)

    /** Artwork already stored for this category, null when the automatic cover stack is used. */
    val storedCover = MutableStateFlow<File?>(null)

    /** Image picked in this session; it replaces [storedCover] once the category is saved. */
    val pickedCover = MutableStateFlow<Uri?>(null)

    private val isRemoveCoverRequested = MutableStateFlow(false)

    val isTrackerEnabled = flow {
        emit(settings.isTrackerEnabled && AppSettings.TRACK_FAVOURITES in settings.trackSources)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    /**
     * What the preview shows: the pending pick wins over the stored file, and a requested
     * reset hides both. Null means the automatic cover stack stays in place.
     */
    val coverPreview: StateFlow<Any?> = combine(pickedCover, storedCover, isRemoveCoverRequested) { picked, stored, removed ->
        picked ?: stored.takeUnless { removed }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val canRemoveCover: StateFlow<Boolean> = combine(pickedCover, storedCover) { picked, stored ->
        picked != null || stored != null
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    init {
        launchLoadingJob(Dispatchers.Default) {
            category.value = if (categoryId != NO_ID) {
                repository.getCategory(categoryId)
            } else {
                null
            }
            storedCover.value = if (categoryId != NO_ID) {
                coverStore.getCoverFile(categoryId)
            } else {
                null
            }
        }
    }

    /**
     * Remembers the picked document, but only once it proves readable: a broken or revoked
     * Uri should be reported while the editor is still open, not swallowed by the save.
     */
    fun setPickedCover(uri: Uri) {
        launchJob(Dispatchers.IO) {
            if (coverStore.canReadImage(uri)) {
                pickedCover.value = uri
                isRemoveCoverRequested.value = false
            } else {
                onCoverRejected.call(Unit)
            }
        }
    }

    fun removeCover() {
        pickedCover.value = null
        isRemoveCoverRequested.value = true
    }

    fun save(
        title: String,
        sortOrder: ListSortOrder,
        isTrackerEnabled: Boolean,
        isVisibleOnShelf: Boolean,
    ) {
        launchLoadingJob(Dispatchers.Default) {
            check(title.isNotEmpty())
            val targetId = if (categoryId == NO_ID) {
                repository.createCategory(title, sortOrder, isTrackerEnabled, isVisibleOnShelf).id
            } else {
                repository.updateCategory(categoryId, title, sortOrder, isTrackerEnabled, isVisibleOnShelf)
                categoryId
            }
            saveCover(targetId)
            onSaved.call(Unit)
        }
    }

    /**
     * Applies the cover after the category exists locally, so a brand-new category can get
     * artwork in the same step.
     */
    private suspend fun saveCover(categoryId: Long) {
        val picked = pickedCover.value
        when {
            picked != null -> coverStore.setCover(categoryId, picked)
            isRemoveCoverRequested.value -> coverStore.clearCover(categoryId)
        }
    }
}
