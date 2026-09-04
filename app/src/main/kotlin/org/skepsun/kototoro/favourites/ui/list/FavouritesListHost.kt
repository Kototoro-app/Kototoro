package org.skepsun.kototoro.favourites.ui.list

import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.util.ext.EventFlow
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.favourites.domain.library.FavouriteCardRow
import org.skepsun.kototoro.favourites.domain.library.FavouritesCardMapper
import org.skepsun.kototoro.favourites.ui.container.FavouriteLibraryUiState
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.ContentActionHostRequest
import org.skepsun.kototoro.list.ui.ContentListHost
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.parsers.model.Content

/**
 * Adapter of one favourites category page (favourites-komikku-alignment Phase 6).
 *
 * It owns no data: [FavouritesContainerViewModel] is the single state holder of the whole
 * favourites screen — the library snapshot, the derived slices, the quick-filter selection
 * and every list action live there. This class only *slices* the shared state into the
 * cards of [categoryId] and forwards the route's callbacks, which is why the page needs no
 * child ViewModel of its own (and why switching a tab re-slices instead of re-querying).
 *
 * Instances are cached by the container, so a category keeps its mapped cards across
 * recompositions, tab switches and configuration changes.
 */
class FavouritesListHost internal constructor(
    val categoryId: Long,
    private val container: FavouritesContainerViewModel,
    cardMapper: FavouritesCardMapper,
    private val quickFilter: FavoritesListQuickFilter,
) : ContentListHost, QuickFilterListener {

    private val libraryState: StateFlow<FavouriteLibraryUiState> = container.libraryState

    override val listMode: StateFlow<ListMode> = container.listMode
    override val gridScale: StateFlow<Float> = container.gridScale
    override val hasMoreItems = MutableStateFlow(false)
    /** Always null: the slice is a static list. The member only exists for the route's
     * paging-capable contract, which the favourites page never exercises. */
    override val pagingContent: Flow<PagingData<ListModel>>? = null
    override val currentSourceTags: StateFlow<Set<SourceTag>> = container.selectedSourceTags
    override val currentGroupTab: StateFlow<BrowseGroupTab> = container.currentGroupTab
    override val onError: EventFlow<Throwable> get() = container.onError
    override val onContentMessage: EventFlow<String> get() = container.onContentMessage
    override val onContentActionHostRequest: EventFlow<ContentActionHostRequest>
        get() = container.onContentActionHostRequest
    override val isLoading: StateFlow<Boolean> get() = container.isLoading

    /** Quick-filter chips of this category (inline tab bar). */
    val topQuickFilter: StateFlow<QuickFilter?> = quickFilter.appliedOptions
        .withSettings()
        .mapLatest { filters -> quickFilter.filterItem(filters) }
        .stateIn(container.listScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Like [topQuickFilter] but ignores the "show quick filters" appearance setting, so the
     * top-bar filter panel keeps offering the same options when the inline tab bar
     * (QuickFilterSection) is hidden by the user.
     */
    val popupQuickFilter: StateFlow<QuickFilter?> = quickFilter.appliedOptions
        .withSettings()
        .mapLatest { filters -> quickFilter.filterItem(filters, ignoreVisibilitySetting = true) }
        .stateIn(container.listScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The cards of this category: entity ids of the shared slice mapped to the card mode
     * the user picked. Mapping runs on [Dispatchers.Default] because a slice is the whole
     * (filtered) library — a few thousand rows at most.
     */
    override val content: StateFlow<List<ListModel>> = combine(
        libraryState,
        container.observeListModeWithTriggers(),
    ) { library, mode ->
        buildCards(library, mode, cardMapper)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = container.listScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = listOf<ListModel>(LoadingState),
        )

    /** Nothing to re-query: the snapshot is Room-invalidation driven. */
    override fun onRefresh() = Unit

    override fun onRetry() = Unit

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) =
        quickFilter.setFilterOption(option, isApplied)

    override fun toggleFilterOption(option: ListFilterOption) = quickFilter.toggleFilterOption(option)

    override fun clearFilter() = container.resetFilters()

    fun removeFromFavourites(ids: Set<Long>) = container.removeFromFavourites(categoryId, ids)

    fun resolveSelectionToMangaIds(ids: Set<Long>): Set<Long> = container.resolveSelectionToMangaIds(ids)

    suspend fun resolveSelectedContents(ids: Collection<Long>): List<Content> =
        container.resolveSelectedContents(ids)

    suspend fun isPinned(ids: Set<Long>): Boolean = container.isPinned(ids)

    fun setPinned(ids: Set<Long>, isPinned: Boolean) = container.setPinned(ids, isPinned)

    fun togglePinned(ids: Set<Long>) = container.togglePinned(ids)

    fun markAsRead(entityIds: Collection<Long>) = container.markAsRead(entityIds)

    fun checkForUpdates() = container.checkForUpdates()

    /** Cards are entity rows, so the list item id *is* the entity id. */
    override fun resolveEntityIdForUiItemId(id: Long): Long? = id

    /** Display projection of the entity, `null` for a row without one (entity organize). */
    override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? =
        libraryState.value.rowsByEntityId[id]?.displayMangaId

    private fun buildCards(
        library: FavouriteLibraryUiState,
        mode: ListMode,
        cardMapper: FavouritesCardMapper,
    ): List<ListModel> {
        if (!library.isInitialized) {
            return listOf(LoadingState)
        }
        val ids = library.visibleIdsByCategory[categoryId]
        if (ids.isNullOrEmpty()) {
            return emptyList()
        }
        val byId = library.rowsByEntityId
        val rows = ArrayList<FavouriteCardRow>(ids.size)
        for (id in ids) {
            byId[id]?.let { rows.add(it) }
        }
        return cardMapper.map(
            rows = rows,
            slice = FavouritesCardMapper.Slice(
                mode = mode,
                pinnedEntityIds = library.pinnedIdsByCategory[categoryId].orEmpty(),
            ),
        )
    }

    private fun Flow<Set<ListFilterOption>>.withSettings(): Flow<Set<ListFilterOption>> =
        with(container) { this@withSettings.combineWithSettings() }
}
