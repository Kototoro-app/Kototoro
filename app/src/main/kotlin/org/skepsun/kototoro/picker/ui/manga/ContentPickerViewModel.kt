package org.skepsun.kototoro.picker.ui.manga

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent

@HiltViewModel
class ContentPickerViewModel @Inject constructor(
    settings: AppSettings,
    mangaDataRepository: ContentDataRepository,
    private val historyRepository: HistoryRepository,
    private val favouritesRepository: FavouritesRepository,
    private val mangaListMapper: ContentListMapper,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
) : ContentListViewModel(settings, mangaDataRepository, localStorageChanges) {

    override val content: StateFlow<List<ListModel>>
        get() = flow {
            emit(loadList())
        }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState))

    override fun onRefresh() = Unit

    override fun onRetry() = Unit

    private suspend fun loadList() = buildList {
        val history = historyRepository.getList(0, Int.MAX_VALUE)
        if (history.isNotEmpty()) {
            add(ListHeader(R.string.history))
            mangaListMapper.toListModelList(this, history, settings.listMode)
        }
        val categories = favouritesRepository.observeCategoriesForLibrary().first()
        for (category in categories) {
            val favorites = favouritesRepository.getContent(category.id)
            if (favorites.isNotEmpty()) {
                add(ListHeader(category.title))
                mangaListMapper.toListModelList(this, favorites, settings.listMode)
            }
        }
    }
}
