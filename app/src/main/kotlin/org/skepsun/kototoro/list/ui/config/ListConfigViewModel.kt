package org.skepsun.kototoro.list.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.sortedByOrdinal
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import kotlinx.coroutines.plus
import javax.inject.Inject

@HiltViewModel
class ListConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settings: AppSettings,
    private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

    private val sectionState = MutableStateFlow<ListConfigSection?>(
        savedStateHandle[AppRouter.KEY_LIST_SECTION],
    )
    private val favoriteSortOrderState = MutableStateFlow<ListSortOrder?>(null)

    val section: ListConfigSection?
        get() = sectionState.value

    fun initialize(section: ListConfigSection) {
        if (sectionState.value == null) {
            sectionState.value = section
        }
        if (section is ListConfigSection.Favorites && favoriteSortOrderState.value == null) {
            favoriteSortOrderState.value = getCategorySortOrder(section.categoryId)
        }
    }

    val listModeState: StateFlow<ListMode> = combine(
        sectionState,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_LIST_MODE,
            valueProducer = { listMode },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_LIST_MODE_HOME,
            valueProducer = { homeListMode },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_LIST_MODE_HISTORY,
            valueProducer = { historyListMode },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_LIST_MODE_SUGGESTIONS,
            valueProducer = { suggestionsListMode },
        ),
    ) { section, generalMode, homeMode, historyMode, suggestionsMode ->
        when (section) {
            ListConfigSection.Home -> homeMode
            ListConfigSection.History -> historyMode
            ListConfigSection.Suggestions -> suggestionsMode
            else -> generalMode
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        settings.listMode,
    )

    val gridSizeState: StateFlow<Int> = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_GRID_SIZE,
        valueProducer = { gridSize },
    )

    val supportsGroupingState: StateFlow<Boolean> = sectionState.map {
        it == ListConfigSection.History || it == ListConfigSection.Updated
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        false,
    )

    val isGroupingAvailableState: StateFlow<Boolean> = combine(
        sectionState,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HISTORY_ORDER,
            valueProducer = { historySortOrder },
        ),
    ) { section, historySortOrder ->
        when (section) {
            ListConfigSection.History -> historySortOrder.isGroupingSupported()
            ListConfigSection.Updated -> true
            else -> false
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        false,
    )

    val isGroupingEnabledState: StateFlow<Boolean> = combine(
        sectionState,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HISTORY_GROUPING,
            valueProducer = { isHistoryGroupingEnabled },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_UPDATED_GROUPING,
            valueProducer = { isUpdatedGroupingEnabled },
        ),
    ) { section, historyGroupingEnabled, updatedGroupingEnabled ->
        when (section) {
            ListConfigSection.History -> historyGroupingEnabled
            ListConfigSection.Updated -> updatedGroupingEnabled
            else -> false
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        false,
    )

    val sortOrdersState: StateFlow<List<ListSortOrder>> = sectionState.map {
        getSortOrdersForSection(it).orEmpty()
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        getSortOrdersForSection(sectionState.value).orEmpty(),
    )

    val selectedSortOrderState: StateFlow<ListSortOrder?> = combine(
        sectionState,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HISTORY_ORDER,
            valueProducer = { historySortOrder },
        ),
        favoriteSortOrderState,
    ) { section, historySortOrder, favoriteSortOrder ->
        when (section) {
            is ListConfigSection.Favorites -> favoriteSortOrder
            ListConfigSection.History -> historySortOrder
            ListConfigSection.Suggestions -> ListSortOrder.RELEVANCE
            else -> null
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        getSelectedSortOrderForSection(sectionState.value),
    )

    var listMode: ListMode
        get() = when (section) {
            ListConfigSection.Home -> settings.homeListMode
            ListConfigSection.History -> settings.historyListMode
            ListConfigSection.Suggestions -> settings.suggestionsListMode
            else -> settings.listMode
        }
        set(value) {
            when (section) {
                ListConfigSection.Home -> settings.homeListMode = value
                ListConfigSection.History -> settings.historyListMode = value
                ListConfigSection.Suggestions -> settings.suggestionsListMode = value
                else -> settings.listMode = value
            }
        }

    var gridSize: Int
        get() = settings.gridSize
        set(value) {
            settings.gridSize = value
        }

    val isGroupingSupported: Boolean
        get() = section == ListConfigSection.History || section == ListConfigSection.Updated

    val isGroupingAvailable: Boolean
        get() = when (section) {
            ListConfigSection.History -> settings.historySortOrder.isGroupingSupported()
            ListConfigSection.Updated -> true
            else -> false
        }

    var isGroupingEnabled: Boolean
        get() = when (section) {
            ListConfigSection.History -> settings.isHistoryGroupingEnabled
            ListConfigSection.Updated -> settings.isUpdatedGroupingEnabled
            else -> false
        }
        set(value) = when (section) {
            ListConfigSection.History -> settings.isHistoryGroupingEnabled = value
            ListConfigSection.Updated -> settings.isUpdatedGroupingEnabled = value
            else -> Unit
        }

    fun updateListMode(value: ListMode) {
        listMode = value
    }

    fun updateGridSize(value: Int) {
        gridSize = value
    }

    fun updateGroupingEnabled(value: Boolean) {
        isGroupingEnabled = value
    }

    fun getSortOrders(): List<ListSortOrder>? = getSortOrdersForSection(sectionState.value)

    private fun getSortOrdersForSection(section: ListConfigSection?): List<ListSortOrder>? = when (section) {
        is ListConfigSection.Favorites -> ListSortOrder.FAVORITES
        ListConfigSection.General -> null
        ListConfigSection.Home -> null
        ListConfigSection.History -> ListSortOrder.HISTORY
        ListConfigSection.Suggestions -> ListSortOrder.SUGGESTIONS
        ListConfigSection.Updated -> null
        null -> null
    }?.sortedByOrdinal()

    fun getSelectedSortOrder(): ListSortOrder? = getSelectedSortOrderForSection(sectionState.value)

    private fun getSelectedSortOrderForSection(section: ListConfigSection?): ListSortOrder? = when (section) {
        is ListConfigSection.Favorites -> favoriteSortOrderState.value ?: getCategorySortOrder(section.categoryId)
        ListConfigSection.General -> null
        ListConfigSection.Home -> null
        ListConfigSection.Updated -> null
        ListConfigSection.History -> settings.historySortOrder
        ListConfigSection.Suggestions -> ListSortOrder.RELEVANCE
        null -> null
    }

    fun setSortOrder(position: Int) {
        val value = getSortOrders()?.getOrNull(position) ?: return
        setSortOrder(value)
    }

    fun setSortOrder(value: ListSortOrder) {
        when (val currentSection = sectionState.value) {
            is ListConfigSection.Favorites -> launchJob {
                favoriteSortOrderState.value = value
                if (currentSection.categoryId == NO_ID) {
                    settings.allFavoritesSortOrder = value
                } else {
                    favouritesRepository.setCategoryOrder(currentSection.categoryId, value)
                }
            }

            ListConfigSection.General -> Unit
            ListConfigSection.Home -> Unit
            ListConfigSection.History -> settings.historySortOrder = value

            ListConfigSection.Suggestions -> Unit
            ListConfigSection.Updated -> Unit
            null -> Unit
        }
    }

    private fun getCategorySortOrder(id: Long): ListSortOrder = if (id == NO_ID) {
        settings.allFavoritesSortOrder
    } else runBlocking {
        runCatchingCancellable {
            favouritesRepository.getCategory(id).order
        }.getOrElse {
            settings.allFavoritesSortOrder
        }
    }
}
