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
        // Always re-assert the section: the VM may be reused across sheets
        // (activity-scoped store), and a stale section would silently route
        // this panel's writes to another section's preferences.
        if (sectionState.value != section) {
            sectionState.value = section
        }
        if (section is ListConfigSection.Favorites && favoriteSortOrderState.value == null) {
            favoriteSortOrderState.value = getCategorySortOrder(section.categoryId)
        }
    }

    /** Observed list modes of the three home rails (history / updates / recommendations). */
    private val homeSectionModesState = combine(
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_LIST_MODE_HISTORY,
            valueProducer = { homeSectionListModeHistory },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_LIST_MODE_UPDATES,
            valueProducer = { homeSectionListModeUpdates },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_LIST_MODE_RECOMMENDATIONS,
            valueProducer = { homeSectionListModeRecommendations },
        ),
    ) { history, updates, recommendations ->
        HomeSectionModes(history, updates, recommendations)
    }

    /** Observed grid sizes of the three home rails. */
    private val homeSectionGridSizesState = combine(
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_GRID_SIZE_HISTORY,
            valueProducer = { homeSectionGridSizeHistory },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_GRID_SIZE_UPDATES,
            valueProducer = { homeSectionGridSizeUpdates },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_GRID_SIZE_RECOMMENDATIONS,
            valueProducer = { homeSectionGridSizeRecommendations },
        ),
    ) { history, updates, recommendations ->
        HomeSectionGridSizes(history, updates, recommendations)
    }

    private data class ScreenListModes(
        val section: ListConfigSection?,
        val general: ListMode,
        val home: ListMode,
        val history: ListMode,
        val suggestions: ListMode,
    )

    private data class HomeSectionModes(
        val history: ListMode,
        val updates: ListMode,
        val recommendations: ListMode,
    )

    private data class HomeSectionGridSizes(
        val history: Int,
        val updates: Int,
        val recommendations: Int,
    )

    val listModeState: StateFlow<ListMode> = combine(
        combine(
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
            ScreenListModes(section, generalMode, homeMode, historyMode, suggestionsMode)
        },
        homeSectionModesState,
    ) { screen, sectionModes ->
        when (screen.section) {
            ListConfigSection.Home -> screen.home
            ListConfigSection.HomeHistory -> sectionModes.history
            ListConfigSection.HomeUpdates -> sectionModes.updates
            ListConfigSection.HomeRecommendations -> sectionModes.recommendations
            ListConfigSection.History -> screen.history
            ListConfigSection.Suggestions -> screen.suggestions
            else -> screen.general
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        settings.listMode,
    )

    val gridSizeState: StateFlow<Int> = combine(
        sectionState,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_GRID_SIZE,
            valueProducer = { gridSize },
        ),
        homeSectionGridSizesState,
    ) { section, globalSize, sectionSizes ->
        when (section) {
            ListConfigSection.HomeHistory -> sectionSizes.history
            ListConfigSection.HomeUpdates -> sectionSizes.updates
            ListConfigSection.HomeRecommendations -> sectionSizes.recommendations
            else -> globalSize
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        settings.gridSize,
    )

    /** Rows per rail page; null for sections that don't page their rails. */
    val railRowsState: StateFlow<Int?> = combine(
        sectionState,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_RAIL_ROWS_HISTORY,
            valueProducer = { homeSectionRailRowsHistory },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_RAIL_ROWS_UPDATES,
            valueProducer = { homeSectionRailRowsUpdates },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_HOME_SECTION_RAIL_ROWS_RECOMMENDATIONS,
            valueProducer = { homeSectionRailRowsRecommendations },
        ),
    ) { section, historyRows, updatesRows, recommendationsRows ->
        when (section) {
            ListConfigSection.HomeHistory -> historyRows
            ListConfigSection.HomeUpdates -> updatesRows
            ListConfigSection.HomeRecommendations -> recommendationsRows
            else -> null
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        null,
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
            ListConfigSection.HomeHistory -> settings.homeSectionListModeHistory
            ListConfigSection.HomeUpdates -> settings.homeSectionListModeUpdates
            ListConfigSection.HomeRecommendations -> settings.homeSectionListModeRecommendations
            ListConfigSection.History -> settings.historyListMode
            ListConfigSection.Suggestions -> settings.suggestionsListMode
            else -> settings.listMode
        }
        set(value) {
            when (section) {
                ListConfigSection.Home -> settings.homeListMode = value
                ListConfigSection.HomeHistory -> settings.homeSectionListModeHistory = value
                ListConfigSection.HomeUpdates -> settings.homeSectionListModeUpdates = value
                ListConfigSection.HomeRecommendations -> settings.homeSectionListModeRecommendations = value
                ListConfigSection.History -> settings.historyListMode = value
                ListConfigSection.Suggestions -> settings.suggestionsListMode = value
                else -> settings.listMode = value
            }
        }

    var gridSize: Int
        get() = when (section) {
            ListConfigSection.HomeHistory -> settings.homeSectionGridSizeHistory
            ListConfigSection.HomeUpdates -> settings.homeSectionGridSizeUpdates
            ListConfigSection.HomeRecommendations -> settings.homeSectionGridSizeRecommendations
            else -> settings.gridSize
        }
        set(value) {
            when (section) {
                ListConfigSection.HomeHistory -> settings.homeSectionGridSizeHistory = value
                ListConfigSection.HomeUpdates -> settings.homeSectionGridSizeUpdates = value
                ListConfigSection.HomeRecommendations -> settings.homeSectionGridSizeRecommendations = value
                else -> settings.gridSize = value
            }
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

    fun updateRailRows(value: Int) {
        when (section) {
            ListConfigSection.HomeHistory -> settings.homeSectionRailRowsHistory = value
            ListConfigSection.HomeUpdates -> settings.homeSectionRailRowsUpdates = value
            ListConfigSection.HomeRecommendations -> settings.homeSectionRailRowsRecommendations = value
            else -> Unit
        }
    }

    fun updateGroupingEnabled(value: Boolean) {
        isGroupingEnabled = value
    }

    fun getSortOrders(): List<ListSortOrder>? = getSortOrdersForSection(sectionState.value)

    private fun getSortOrdersForSection(section: ListConfigSection?): List<ListSortOrder>? = when (section) {
        is ListConfigSection.Favorites -> ListSortOrder.FAVORITES
        ListConfigSection.General -> null
        ListConfigSection.Home -> null
        ListConfigSection.HomeHistory, ListConfigSection.HomeUpdates, ListConfigSection.HomeRecommendations -> null
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
        ListConfigSection.HomeHistory, ListConfigSection.HomeUpdates, ListConfigSection.HomeRecommendations -> null
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
            ListConfigSection.HomeHistory, ListConfigSection.HomeUpdates, ListConfigSection.HomeRecommendations -> Unit
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
