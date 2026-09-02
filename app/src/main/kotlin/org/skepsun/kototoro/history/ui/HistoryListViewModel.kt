package org.skepsun.kototoro.history.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.calculateTimeAgo
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.domain.HistoryListQuickFilter
import org.skepsun.kototoro.history.domain.MarkAsReadUseCase
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.InfoModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant
import javax.inject.Inject
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import kotlinx.coroutines.flow.SharedFlow
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.stats.data.StatsRepository
import org.skepsun.kototoro.stats.domain.StatsDashboard
import org.skepsun.kototoro.stats.domain.StatsPeriod

private const val PAGE_SIZE = 32

private data class HistoryUiParams(
    val order: ListSortOrder,
    val filters: Set<ListFilterOption>,
    val effectiveFilters: Set<ListFilterOption>,
    val grouped: Boolean,
    val mode: ListMode,
    val incognito: Boolean,
    val groupTab: BrowseGroupTab,
    val sourceTags: Set<SourceTag>,
    val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
    val spaceId: SpaceId?,
)

@HiltViewModel
class HistoryListViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: HistoryRepository,
    settings: AppSettings,
    private val mangaListMapper: ContentListMapper,
    private val favouritesRepository: FavouritesRepository,
    private val markAsReadUseCase: MarkAsReadUseCase,
    private val quickFilter: HistoryListQuickFilter,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    dataRepository: ContentDataRepository,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
    private val statsRepository: StatsRepository,
    private val historyLibrarySnapshotStore: org.skepsun.kototoro.history.domain.library.HistoryLibrarySnapshotStore,
    private val historyCardMapper: org.skepsun.kototoro.history.domain.library.HistoryCardMapper,
    private val spaceContentPolicy: org.skepsun.kototoro.space.domain.SpaceContentPolicy,
    spaceBrowseScope: SpaceBrowseScope,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener, SpaceBindableViewModel {
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

    override val isFilterBarVisible = MutableStateFlow(true)
    private val activeSpaceScope = spaceBinding.spaceId


    override val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
        spaceGroupTab = spaceBinding.groupTab,
        coroutineScope = viewModelScope + Dispatchers.Default,
    )
    override fun bindSpace(spaceId: SpaceId?) = spaceBinding.bindSpace(spaceId)
    override val currentSourceTags = globalFavoritesState.selectedSourceTags

    override fun setSelectedGroupTab(tab: BrowseGroupTab) {
        globalFavoritesState.setSelectedGroupTab(tab)
    }

    override fun setSelectedSourceTags(tags: Set<SourceTag>) {
        globalFavoritesState.setSelectedSourceTags(tags)
    }

    private val sortOrder: StateFlow<ListSortOrder> = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.IO,
        key = AppSettings.KEY_HISTORY_ORDER,
        valueProducer = { historySortOrder },
    )

    override val listMode = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_LIST_MODE,
        valueProducer = { settings.listMode },
    )

    private val isGroupingEnabled = settings.observeAsFlow(
        key = AppSettings.KEY_HISTORY_GROUPING,
        valueProducer = { isHistoryGroupingEnabled },
    ).combine(sortOrder) { g, s ->
        g && s.isGroupingSupported()
    }

    val isStatsEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_STATS_ENABLED,
        valueProducer = { isStatsEnabled },
    )

    /** Reading statistics summary shown at the top of the history page. */
    val statsSummary: StateFlow<StatsDashboard?> = isStatsEnabled
        .flatMapLatest { enabled ->
            if (enabled) {
                statsRepository.observeDashboard(StatsPeriod.WEEK)
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val headerQuickFilter: StateFlow<QuickFilter?> = combine(
        quickFilter.appliedOptions,
        // Re-emit when the quick-filter visibility toggle changes so the flatMapLatest
        // below re-evaluates against the fresh setting (hides/shows the inline bar).
        settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled },
    ) { filters, _ -> filters }
        .flatMapLatest { selectedOptions ->
            flow {
                if (!settings.isQuickFilterEnabled) {
                    emit(null)
                    return@flow
                }
                emit(quickFilter.filterItem(selectedOptions))
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    private val uiParams = combine(
        sortOrder,
        quickFilter.appliedOptions,
        isGroupingEnabled,
        observeListModeWithTriggers(),
        settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) { isIncognitoModeEnabled },
        this.currentGroupTab,
        this.currentSourceTags,
        settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
            .flatMapLatest { id ->
                if (id == -1L) flowOf(null)
                else sourcePresetsRepository.observe(id)
            },
        settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
        activeSpaceScope,
    ) { values: Array<Any?> ->
        val order = values[0] as ListSortOrder
        val filters = values[1] as Set<ListFilterOption>
        val grouped = values[2] as Boolean
        val mode = values[3] as ListMode
        val incognito = values[4] as Boolean
        val groupTab = values[5] as BrowseGroupTab
        val sourceTags = values[6] as Set<SourceTag>
        val preset = values[7] as? org.skepsun.kototoro.explore.data.SourcePreset
        val skipNsfw = values[8] as Boolean
        val spaceId = values[9] as? SpaceId
        HistoryUiParams(
            order = order,
            filters = filters,
            effectiveFilters = if (skipNsfw) filters + ListFilterOption.SFW else filters,
            grouped = grouped,
            mode = mode,
            incognito = incognito,
            groupTab = groupTab,
            sourceTags = sourceTags,
            preset = preset,
            spaceId = spaceId,
        )
    }.distinctUntilChanged()

    /**
     * The derived rows (history-updates-feed komikku-alignment Phase H4):
     * snapshot -> in-memory derivation. Derived once here and re-used by the
     * content builder and the removal/navigation index, so switching the sort
     * order, a filter, the tab or the space never re-queries the database.
     */
    private val derivedRows: StateFlow<List<org.skepsun.kototoro.history.domain.library.HistoryCardEntry>> =
        combine(
            historyLibrarySnapshotStore.observe(),
            uiParams,
        ) { snapshot, params ->
            val space = params.spaceId?.let { spaceId ->
                org.skepsun.kototoro.history.domain.library.HistoryLibraryDeriver.SpaceScope(
                    allowedTypes = spaceContentPolicy.allowedTypes(spaceId),
                    classifiedTypes = org.skepsun.kototoro.space.domain.BuiltInSpaces.contexts
                        .flatMapTo(LinkedHashSet()) { context -> context.allowedContentTypes },
                    allowedSources = spaceContentPolicy.allowedSourceNames(spaceId),
                )
            }
            org.skepsun.kototoro.history.domain.library.HistoryLibraryDeriver.derive(
                org.skepsun.kototoro.history.domain.library.HistoryLibraryDeriver.Input(
                    snapshot = snapshot,
                    order = params.order,
                    filters = params.effectiveFilters,
                    excludedNsfw = settings.isHistoryExcludeNsfw || params.effectiveFilters != params.filters,
                    groupTab = params.groupTab,
                    sourceTags = params.sourceTags,
                    presetSources = params.preset?.sources?.toSet(),
                    space = space,
                ),
            )
        }.mapLatest { derived ->
            derived.visibleRows
        }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    private val rowsByUiId: Map<Long, org.skepsun.kototoro.history.domain.library.HistoryCardEntry>
        get() = derivedRows.value.associateBy(
            org.skepsun.kototoro.history.domain.library.HistoryCardEntry::uiId,
        )

    /**
     * The one and only history list: derived rows mapped to cards, with the
     * order-aware date headers (grouping on) and the incognito banner. The
     * paging chain (Pager + per-page aggregate resolution + insertSeparators)
     * is gone; the snapshot store is Room-invalidation driven, so refreshes
     * are implicit.
     */
    override val content: StateFlow<List<ListModel>> = combine(
        derivedRows,
        uiParams,
        settings.observeAsFlow(AppSettings.KEY_PROGRESS_INDICATORS) { progressIndicatorMode },
    ) { rows, params, progressMode ->
        buildStaticContent(rows, params, progressMode)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    /** Nothing to re-query: the snapshot is Room-invalidation driven. */
    override fun onRefresh() = Unit

    override fun onRetry() = Unit

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
        quickFilter.setFilterOption(option, isApplied)
    }

    override fun toggleFilterOption(option: ListFilterOption) {
        quickFilter.toggleFilterOption(option)
    }

    override fun clearFilter() {
        quickFilter.clearFilter()
    }

    fun clearHistory(minDate: Instant?) {
        launchJob(Dispatchers.Default) {
            val stringRes = if (minDate == null) {
                repository.clear()
                R.string.history_cleared
            } else {
                repository.deleteAfter(minDate.toEpochMilli())
                R.string.removed_from_history
            }
            onActionDone.call(ReversibleAction(stringRes, null))
        }
    }

    fun removeNotFavorite() {
        launchJob(Dispatchers.Default) {
            repository.deleteNotFavorite()
            onActionDone.call(ReversibleAction(R.string.removed_from_history, null))
        }
    }

    fun removeFromHistory(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        launchJob(Dispatchers.Default) {
            // the ui id is the derived row id; the delete path resolves the
            // owning work_history entity from the display manga id
            val mangaIds = ids.flatMapTo(LinkedHashSet()) { uiId ->
                val row = rowsByUiId[uiId]
                listOfNotNull(row?.displayMangaId ?: row?.anchorMangaId ?: uiId)
            }
            val handle = repository.delete(mangaIds)
            onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
        }
    }

    fun markAsRead(items: Set<Content>) {
        launchLoadingJob(Dispatchers.Default) {
            markAsReadUseCase(items)
        }
    }


    fun requestMoreItems() {
        // The static list has no prefetch: the whole snapshot is already loaded.
    }

    override fun resolveEntityIdForUiItemId(id: Long): Long? {
        return rowsByUiId[id]?.entityId
    }

    override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
        val row = rowsByUiId[id] ?: return null
        return row.displayMangaId ?: row.anchorMangaId
    }

    /**
     * Builds the static history list: quick-filter chip, incognito banner, the
     * order-aware headers (grouping on), the cards, and the empty states.
     */
    private suspend fun buildStaticContent(
        rows: List<org.skepsun.kototoro.history.domain.library.HistoryCardEntry>,
        params: HistoryUiParams,
        progressMode: org.skepsun.kototoro.core.prefs.ProgressIndicatorMode,
    ): List<ListModel> {
        if (rows.isEmpty()) {
            return if (params.filters.isEmpty() && params.groupTab == BrowseGroupTab.All &&
                params.sourceTags.isEmpty()
            ) {
                listOf(quickFilter.filterItem(params.filters), getEmptyState(hasFilters = false))
                    .filterNotNull()
            } else {
                listOfNotNull(
                    quickFilter.filterItem(params.filters),
                    getEmptyState(hasFilters = true),
                )
            }
        }
        val cards = historyCardMapper.map(
            rows,
            org.skepsun.kototoro.history.domain.library.HistoryCardMapper.Slice(
                mode = params.mode,
                progressMode = progressMode,
            ),
        )
        val result = ArrayList<ListModel>(cards.size + 8)
        quickFilter.filterItem(params.filters)?.let(result::add)
        if (params.incognito) {
            result += InfoModel(
                key = AppSettings.KEY_INCOGNITO_MODE,
                title = R.string.incognito_mode,
                text = R.string.incognito_mode_hint,
                icon = R.drawable.ic_incognito,
            )
        }
        if (params.grouped) {
            var prevHeader: ListHeader? = null
            val headers = HashMap<Long, ListHeader?>(cards.size)
            for (row in rows) {
                headers[row.uiId] = headerFor(row, params.order)
            }
            for (card in cards) {
                val header = headers[card.id]
                if (header != prevHeader) {
                    header?.let(result::add)
                    prevHeader = header
                }
                result += card
            }
        } else {
            result.addAll(cards)
        }
        return result
    }

    /** The order-aware group header of one row (null when the order has none). */
    private fun headerFor(
        row: org.skepsun.kototoro.history.domain.library.HistoryCardEntry,
        order: ListSortOrder,
    ): ListHeader? = when (order) {
        ListSortOrder.LAST_READ,
        ListSortOrder.LONG_AGO_READ -> calculateTimeAgo(java.time.Instant.ofEpochMilli(row.updatedAt))?.let {
            ListHeader(it)
        } ?: ListHeader(R.string.unknown)

        ListSortOrder.OLDEST,
        ListSortOrder.NEWEST -> calculateTimeAgo(java.time.Instant.ofEpochMilli(row.createdAt))?.let {
            ListHeader(it)
        } ?: ListHeader(R.string.unknown)

        ListSortOrder.UNREAD,
        ListSortOrder.PROGRESS -> ListHeader(
            when {
                ReadingProgress.isCompleted(row.percent) -> R.string.status_completed
                row.percent in 0f..0.01f -> R.string.status_planned
                row.percent in 0f..1f -> R.string.status_reading
                else -> R.string.unknown
            },
        )

        ListSortOrder.ALPHABETIC,
        ListSortOrder.ALPHABETIC_REVERSE,
        ListSortOrder.RELEVANCE,
        ListSortOrder.NEW_CHAPTERS,
        ListSortOrder.UPDATED,
        ListSortOrder.RATING -> null
    }

    private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
        EmptyState(
            icon = R.drawable.ic_empty_history,
            textPrimary = R.string.nothing_found,
            textSecondary = R.string.text_empty_holder_secondary_filtered,
            actionStringRes = R.string.reset_filter,
        )
    } else {
        EmptyState(
            icon = R.drawable.ic_empty_history,
            textPrimary = R.string.text_history_holder_primary,
            textSecondary = R.string.text_history_holder_secondary,
            actionStringRes = 0,
        )
    }
}
