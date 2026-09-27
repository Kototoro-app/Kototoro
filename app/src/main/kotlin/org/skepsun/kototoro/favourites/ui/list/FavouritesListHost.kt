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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.util.ext.EventFlow
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.favourites.domain.library.FavouriteCardRow
import org.skepsun.kototoro.favourites.domain.library.FavouritesCardMapper
import org.skepsun.kototoro.favourites.domain.library.FavouritesShelfState
import org.skepsun.kototoro.favourites.domain.library.selectFavouritesShelfRows
import org.skepsun.kototoro.favourites.ui.container.FavouriteLibraryUiState
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.ContentActionHostRequest
import org.skepsun.kototoro.list.ui.ContentListHost
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.EmptyState
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

    val libraryState: StateFlow<FavouriteLibraryUiState> get() = container.libraryState

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

    /**
     * The "continue reading" shelf of this category, drawn above the grid. Empty when the
     * user turned the shelf off or nothing in the (filtered) slice qualifies.
     */
    val shelf: StateFlow<FavouritesShelfState> = combine(
        libraryState,
        container.isShelfEnabled,
    ) { library, isEnabled ->
        buildShelf(library, isEnabled, cardMapper)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = container.listScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FavouritesShelfState(),
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
            return listOf(emptyStateOf(library))
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

    /**
     * An empty slice is either a category with nothing in it, or one whose works the
     * space / filters hide — the latter gets "nothing found" with a reset action, so the
     * page never looks empty while filters (e.g. Downloaded while offline) hide the library.
     */
    private fun emptyStateOf(library: FavouriteLibraryUiState): EmptyState {
        val hasWorks = if (categoryId == NO_ID) {
            library.allEntityIds.isNotEmpty()
        } else {
            library.membershipsByCategory[categoryId].orEmpty().isNotEmpty()
        }
        return if (hasWorks) {
            EmptyState(
                icon = R.drawable.ic_empty_favourites,
                textPrimary = R.string.nothing_found,
                textSecondary = R.string.text_empty_holder_secondary_filtered,
                actionStringRes = R.string.reset_filter,
            )
        } else {
            EmptyState(
                icon = R.drawable.ic_empty_favourites,
                textPrimary = R.string.text_empty_holder_primary,
                textSecondary = R.string.you_have_not_favourites_yet,
                actionStringRes = 0,
            )
        }
    }

    private fun buildShelf(
        library: FavouriteLibraryUiState,
        isEnabled: Boolean,
        cardMapper: FavouritesCardMapper,
    ): FavouritesShelfState {
        if (!isEnabled || !library.isInitialized) {
            return FavouritesShelfState()
        }
        val ids = library.visibleIdsByCategory[categoryId].orEmpty()
        val byId = library.rowsByEntityId
        val rows = ids.mapNotNull { byId[it] }
        val items = cardMapper.map(
            rows = selectFavouritesShelfRows(rows),
            slice = FavouritesCardMapper.Slice(mode = ListMode.GRID),
        ).filterIsInstance<ContentGridModel>()
        return FavouritesShelfState(
            items = items,
            totalCount = rows.size,
            updatedCount = rows.count { it.newChapters > 0 },
        )
    }

    private fun Flow<Set<ListFilterOption>>.withSettings(): Flow<Set<ListFilterOption>> =
        with(container) { this@withSettings.combineWithSettings() }
}
