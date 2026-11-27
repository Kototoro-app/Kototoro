package org.skepsun.kototoro.explore.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedConfigRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.explore.domain.DiscoveryFeedLibraryMembership
import org.skepsun.kototoro.explore.domain.FavouritesDiscoveryFeedLibraryMembership
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowLoadResult
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowLoader
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

@HiltViewModel
class DiscoveryFeedViewModel private constructor(
    private val configRepository: DiscoveryFeedConfigRepository,
    private val rowLoader: DiscoveryFeedRowLoader,
    private val settings: AppSettings,
    private val contentListMapper: ContentListMapper?,
    private val contentSourcesRepository: ContentSourcesRepository?,
    private val savedFiltersRepository: SavedFiltersRepository?,
    private val libraryMembership: DiscoveryFeedLibraryMembership?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : BaseViewModel() {

    @Inject
    constructor(
        configRepository: DiscoveryFeedConfigRepository,
        rowLoader: DiscoveryFeedRowLoader,
        settings: AppSettings,
        contentListMapper: ContentListMapper,
        contentSourcesRepository: ContentSourcesRepository,
        savedFiltersRepository: SavedFiltersRepository,
        libraryMembership: FavouritesDiscoveryFeedLibraryMembership,
    ) : this(
        configRepository = configRepository,
        rowLoader = rowLoader,
        settings = settings,
        contentListMapper = contentListMapper,
        contentSourcesRepository = contentSourcesRepository,
        savedFiltersRepository = savedFiltersRepository,
        libraryMembership = libraryMembership,
        marker = Unit,
    )

    internal constructor(
        configRepository: DiscoveryFeedConfigRepository,
        rowLoader: DiscoveryFeedRowLoader,
        settings: AppSettings,
        libraryMembership: DiscoveryFeedLibraryMembership? = null,
    ) : this(
        configRepository = configRepository,
        rowLoader = rowLoader,
        settings = settings,
        contentListMapper = null,
        contentSourcesRepository = null,
        savedFiltersRepository = null,
        libraryMembership = libraryMembership,
        marker = Unit,
    )

    private val rowsFlow: StateFlow<List<DiscoveryFeedRow>> = configRepository.observeRows()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = configRepository.getRows(),
        )

    val availableSources: StateFlow<List<ContentSource>> = contentSourcesRepository
        ?.observeEnabledSources()
        ?.map { infos ->
            infos.map { it.mangaSource }.distinctBy { it.name }
        }
        ?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = contentSourcesRepository.observeEnabledSources().value
                .map { it.mangaSource }
                .distinctBy { it.name },
        )
        ?: MutableStateFlow(emptyList())

    private val rowStates = MutableStateFlow<Map<String, DiscoveryFeedRowState>>(emptyMap())
    private val rowCardModels = MutableStateFlow<Map<String, List<ContentGridModel>>>(emptyMap())
    private val isRefreshing = MutableStateFlow(false)
    private val selection = MutableStateFlow(FeedSelection())
    private val libraryIds = MutableStateFlow<Set<Long>>(emptySet())
    private val hideInLibrary: StateFlow<Boolean> = configRepository.observeHideInLibrary()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = configRepository.isHideInLibrary(),
        )
    private val displayFilter = combine(hideInLibrary, libraryIds, selection) { hide, library, selected ->
        FeedDisplayFilter(hideInLibrary = hide, libraryIds = library, selection = selected)
    }

    private val jobsLock = Any()
    private val inFlightJobs = HashMap<String, Job>()
    private var refreshAllJob: Job? = null

    val uiState: StateFlow<DiscoveryFeedUiState> = combine(
        rowsFlow,
        rowStates,
        rowCardModels,
        isRefreshing,
        displayFilter,
    ) { allRows, states, cardModels, refreshing, filter ->
        val validIds = allRows.mapTo(HashSet(allRows.size)) { it.id }
        val filteredStates = if (states.keys.all { it in validIds }) {
            states
        } else {
            states.filterKeys { it in validIds }
        }
        val resolvedCardModels = buildMap(filteredStates.size) {
            filteredStates.forEach { (rowId, state) ->
                if (state is DiscoveryFeedRowState.Loaded) {
                    val mapped = cardModels[rowId]
                    val models = if (mapped != null && mapped.size == state.items.size) {
                        mapped
                    } else {
                        state.items.map(::fallbackGridModel)
                    }
                    put(
                        rowId,
                        if (filter.hideInLibrary && filter.libraryIds.isNotEmpty()) {
                            models.filterNot { it.manga.id in filter.libraryIds }
                        } else {
                            models
                        },
                    )
                }
            }
        }
        val visibleRows = allRows.filterNot { it.isHidden }
        val selected = filter.selection.prunedTo(visibleRows, resolvedCardModels)
        DiscoveryFeedUiState(
            allRows = allRows,
            visibleRows = visibleRows,
            rowStates = filteredStates,
            rowCardModels = resolvedCardModels,
            isRefreshing = refreshing,
            hideInLibrary = filter.hideInLibrary,
            selectedIds = selected.ids,
            selectionRowId = selected.rowId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DiscoveryFeedUiState(
            allRows = rowsFlow.value,
            visibleRows = rowsFlow.value.filterNot { it.isHidden },
            hideInLibrary = hideInLibrary.value,
        ),
    )

    init {
        viewModelScope.launch {
            settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }
                .drop(1)
                .collect {
                    cancelInFlightLoads()
                    rowLoader.invalidateAll()
                    rowStates.value = emptyMap()
                    rowCardModels.value = emptyMap()
                }
        }
        if (contentListMapper != null) {
            viewModelScope.launch {
                contentListMapper.observeDisplayChanges().collect {
                    remapAllLoadedRows()
                }
            }
        }
        if (libraryMembership != null) {
            viewModelScope.launch {
                libraryMembership.observeChanges().collect {
                    refreshLibraryIds()
                }
            }
        }
    }

    fun setHideInLibrary(hide: Boolean) {
        configRepository.setHideInLibrary(hide)
    }

    /** Long press on a card, or a tap while selecting: adds or removes it, in whichever row it is. */
    fun toggleSelection(rowId: String, contentId: Long) {
        selection.update { current ->
            val ids = if (contentId in current.ids) current.ids - contentId else current.ids + contentId
            FeedSelection(ids = ids, rowId = rowId.takeIf { ids.isNotEmpty() })
        }
    }

    /** Adds every card of the row the selection last changed in; other rows keep their picks. */
    fun selectAllInCurrentRow() {
        // The raw selection, not uiState: that one trails a toggle made just before.
        val rowId = selection.value.rowId ?: return
        val rowIds = uiState.value.cardModelsOf(rowId).map { it.manga.id }
        if (rowIds.isEmpty()) return
        selection.update { current -> current.copy(ids = current.ids + rowIds, rowId = rowId) }
    }

    fun clearSelection() {
        selection.value = FeedSelection()
    }

    /** Selected works in row order, each once even when it shows in several rows. */
    fun selectedContents(): List<Content> {
        val state = uiState.value
        if (state.selectedIds.isEmpty()) return emptyList()
        val seen = HashSet<Long>(state.selectedIds.size)
        return buildList {
            for (row in state.visibleRows) {
                for (model in state.cardModelsOf(row.id)) {
                    val content = model.manga
                    if (content.id in state.selectedIds && seen.add(content.id)) {
                        add(content)
                    }
                }
            }
        }
    }

    /** Splits the selection into works to add and works that are already in the library. */
    suspend fun planBulkAddToLibrary(): DiscoveryFeedBulkAddPlan {
        val selected = selectedContents()
        if (selected.isEmpty()) return DiscoveryFeedBulkAddPlan(toAdd = emptyList(), alreadyInLibrary = 0)
        val inLibrary = libraryMembership?.libraryIdsOf(selected.map { it.id }).orEmpty()
        val (already, toAdd) = selected.partition { it.id in inLibrary }
        return DiscoveryFeedBulkAddPlan(toAdd = toAdd, alreadyInLibrary = already.size)
    }

    /**
     * Called once the category dialog closes: counts the planned works that are now in the library. The
     * selection ends unless nothing was added, so closing the dialog without a category keeps the picks.
     */
    suspend fun completeBulkAddToLibrary(plan: DiscoveryFeedBulkAddPlan): DiscoveryFeedBulkAddResult {
        val membership = libraryMembership
        val ids = plan.toAdd.map { it.id }
        var added = if (ids.isEmpty() || membership == null) 0 else membership.libraryIdsOf(ids).size
        if (added == 0 && ids.isNotEmpty() && membership != null) {
            // The dialog writes each category toggle in the background, so a tap on Done right after a
            // toggle can close it before the write lands; give that write a moment.
            withTimeoutOrNull(BULK_ADD_WRITE_GRACE_MS) { membership.observeChanges().first() }
            added = membership.libraryIdsOf(ids).size
        }
        val result = DiscoveryFeedBulkAddResult(added = added, skipped = plan.alreadyInLibrary)
        if (added > 0 || plan.toAdd.isEmpty()) {
            clearSelection()
        }
        refreshLibraryIds()
        return result
    }

    suspend fun getSavedFilters(source: ContentSource): List<PersistableFilter> {
        return savedFiltersRepository?.getAll(source).orEmpty()
    }

    suspend fun findSavedFilter(source: ContentSource, id: Int): PersistableFilter? {
        return savedFiltersRepository?.find(source, id)
    }

    fun onRowVisible(rowId: String) {
        val row = findRow(rowId)?.takeUnless { it.isHidden } ?: return
        val cached = rowLoader.getCached(row)
        if (cached != null) {
            rowStates.update { current ->
                current + (rowId to DiscoveryFeedRowState.Loaded(cached.items, cached.fetchedAtMs))
            }
            scheduleRowCardMapping(rowId, cached.items)
            return
        }
        synchronized(jobsLock) {
            if (inFlightJobs[rowId]?.isActive == true) {
                return
            }
        }
        if (rowStates.value[rowId] is DiscoveryFeedRowState.Error) {
            return
        }
        launchRowLoad(row, forceRefresh = false)
    }

    fun retryRow(rowId: String) {
        val row = findRow(rowId) ?: return
        cancelRowJob(rowId)
        rowLoader.invalidate(rowId)
        launchRowLoad(row, forceRefresh = true)
    }

    fun refreshAll() {
        cancelInFlightJobs(resetLoadingToIdle = false)
        rowLoader.invalidateAll()
        val targets = currentRows().filterNot { it.isHidden }
        if (targets.isEmpty()) {
            isRefreshing.value = false
            return
        }
        isRefreshing.value = true
        lateinit var batchJob: Job
        batchJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val jobs = targets.map { row ->
                    launchRowLoad(row, forceRefresh = true)
                }
                jobs.joinAll()
            } finally {
                synchronized(jobsLock) {
                    if (refreshAllJob === batchJob) {
                        refreshAllJob = null
                        isRefreshing.value = false
                    }
                }
            }
        }
        synchronized(jobsLock) {
            refreshAllJob = batchJob
        }
        batchJob.start()
    }

    fun cancelInFlightLoads() {
        cancelInFlightJobs(resetLoadingToIdle = true)
    }

    fun addRow(
        sourceName: String,
        displayName: String,
        savedFilterId: Int? = null,
        query: String? = null,
    ): DiscoveryFeedRow {
        return configRepository.addRow(
            sourceName = sourceName,
            displayName = displayName,
            savedFilterId = savedFilterId,
            query = query,
        )
    }

    fun updateRow(
        rowId: String,
        displayName: String,
        savedFilterId: Int? = null,
        query: String? = null,
        sourceName: String? = null,
    ): DiscoveryFeedRow? {
        val previous = findRow(rowId)
        val updated = configRepository.updateRow(
            rowId = rowId,
            displayName = displayName,
            savedFilterId = savedFilterId,
            query = query,
            sourceName = sourceName,
        ) ?: return null
        val requestChanged = previous == null ||
            previous.sourceName != updated.sourceName ||
            previous.savedFilterId != updated.savedFilterId ||
            previous.normalizedQuery != updated.normalizedQuery
        if (requestChanged) {
            cancelRowJob(rowId)
            rowLoader.invalidate(rowId)
            rowStates.update { it - rowId }
            rowCardModels.update { it - rowId }
        }
        return updated
    }

    fun reorderRows(orderedIds: List<String>): List<DiscoveryFeedRow> {
        return configRepository.reorderRows(orderedIds)
    }

    fun moveRowUp(rowId: String): List<DiscoveryFeedRow> {
        return moveRowByOffset(rowId, delta = -1)
    }

    fun moveRowDown(rowId: String): List<DiscoveryFeedRow> {
        return moveRowByOffset(rowId, delta = 1)
    }

    private fun moveRowByOffset(rowId: String, delta: Int): List<DiscoveryFeedRow> {
        val current = currentRows()
        val index = current.indexOfFirst { it.id == rowId }
        val targetIndex = index + delta
        if (index < 0 || targetIndex !in current.indices) {
            return current
        }
        val reorderedIds = current.mapTo(ArrayList(current.size)) { it.id }
        val moved = reorderedIds.removeAt(index)
        reorderedIds.add(targetIndex, moved)
        return configRepository.reorderRows(reorderedIds)
    }

    fun toggleRowHidden(rowId: String): DiscoveryFeedRow? {
        val updated = configRepository.toggleRowHidden(rowId) ?: return null
        if (updated.isHidden) {
            cancelRowJob(rowId)
            rowStates.update { current ->
                if (current[rowId] is DiscoveryFeedRowState.Loading) {
                    current + (rowId to DiscoveryFeedRowState.Idle)
                } else {
                    current
                }
            }
        }
        return updated
    }

    fun deleteRow(rowId: String): Boolean {
        cancelRowJob(rowId)
        rowLoader.invalidate(rowId)
        rowStates.update { it - rowId }
        rowCardModels.update { it - rowId }
        return configRepository.deleteRow(rowId)
    }

    override fun onCleared() {
        cancelInFlightLoads()
        super.onCleared()
    }

    private fun launchRowLoad(
        row: DiscoveryFeedRow,
        forceRefresh: Boolean,
    ): Job {
        val rowId = row.id
        rowStates.update { current ->
            current + (rowId to DiscoveryFeedRowState.Loading)
        }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = rowLoader.loadRow(row, forceRefresh = forceRefresh)
                val nextState = when (result) {
                    is DiscoveryFeedRowLoadResult.Loaded -> DiscoveryFeedRowState.Loaded(
                        items = result.items,
                        fetchedAtMs = result.fetchedAtMs,
                    )
                    is DiscoveryFeedRowLoadResult.Error -> DiscoveryFeedRowState.Error(
                        throwable = result.throwable,
                    )
                }
                if (nextState is DiscoveryFeedRowState.Loaded) {
                    updateLibraryIdsFor(nextState.items)
                    val mapped = mapRowCards(nextState.items)
                    rowCardModels.update { current -> current + (rowId to mapped) }
                } else {
                    rowCardModels.update { current -> current - rowId }
                }
                rowStates.update { current ->
                    current + (rowId to nextState)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                synchronized(jobsLock) {
                    if (inFlightJobs[rowId] === job) {
                        inFlightJobs.remove(rowId)
                    }
                }
            }
        }
        val previous = synchronized(jobsLock) {
            inFlightJobs.put(rowId, job)
        }
        previous?.cancel()
        job.start()
        return job
    }

    private fun scheduleRowCardMapping(rowId: String, items: List<Content>) {
        if (contentListMapper == null) {
            rowCardModels.update { current ->
                current + (rowId to items.map(::fallbackGridModel))
            }
            return
        }
        viewModelScope.launch {
            val mapped = mapRowCards(items)
            rowCardModels.update { current -> current + (rowId to mapped) }
        }
    }

    private suspend fun updateLibraryIdsFor(items: List<Content>) {
        val membership = libraryMembership ?: return
        if (items.isEmpty()) return
        val ids = items.mapTo(HashSet(items.size)) { it.id }
        val inLibrary = try {
            membership.libraryIdsOf(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        libraryIds.update { current -> (current - ids) + inLibrary }
    }

    private suspend fun refreshLibraryIds() {
        val membership = libraryMembership ?: return
        val ids = rowStates.value.values.flatMapTo(HashSet()) { state ->
            (state as? DiscoveryFeedRowState.Loaded)?.items?.map { it.id }.orEmpty()
        }
        libraryIds.value = if (ids.isEmpty()) {
            emptySet()
        } else {
            try {
                membership.libraryIdsOf(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
        }
    }

    private suspend fun remapAllLoadedRows() {
        if (contentListMapper == null) return
        val loadedEntries = rowStates.value.entries
            .mapNotNull { (rowId, state) ->
                (state as? DiscoveryFeedRowState.Loaded)?.let { rowId to it.items }
            }
        if (loadedEntries.isEmpty()) return
        val remapped = buildMap(loadedEntries.size) {
            for ((rowId, items) in loadedEntries) {
                put(rowId, mapRowCards(items))
            }
        }
        rowCardModels.update { current -> current + remapped }
    }

    private suspend fun mapRowCards(items: List<Content>): List<ContentGridModel> {
        if (items.isEmpty()) return emptyList()
        val mapper = contentListMapper ?: return items.map(::fallbackGridModel)
        return withContext(Dispatchers.Default) {
            mapper.toListModelList(items, ListMode.GRID).filterIsInstance<ContentGridModel>()
        }
    }

    private fun fallbackGridModel(content: Content): ContentGridModel {
        return ContentGridModel(
            manga = content,
            override = null,
            subtitle = content.altTitles.firstOrNull(),
            counter = 0,
            progress = null,
            isFavorite = false,
            isSaved = false,
        )
    }

    private fun cancelRowJob(rowId: String) {
        val job = synchronized(jobsLock) {
            inFlightJobs.remove(rowId)
        }
        job?.cancel()
    }

    private fun cancelInFlightJobs(resetLoadingToIdle: Boolean) {
        val jobsToCancel: List<Job>
        val batchToCancel: Job?
        synchronized(jobsLock) {
            batchToCancel = refreshAllJob
            refreshAllJob = null
            jobsToCancel = inFlightJobs.values.toList()
            inFlightJobs.clear()
        }
        batchToCancel?.cancel()
        jobsToCancel.forEach { it.cancel() }
        isRefreshing.value = false
        if (resetLoadingToIdle) {
            rowStates.update { current ->
                if (current.values.none { it is DiscoveryFeedRowState.Loading }) {
                    current
                } else {
                    current.mapValues { (_, state) ->
                        if (state is DiscoveryFeedRowState.Loading) {
                            DiscoveryFeedRowState.Idle
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    private fun currentRows(): List<DiscoveryFeedRow> {
        return configRepository.getRows()
    }

    private fun findRow(rowId: String): DiscoveryFeedRow? {
        return configRepository.getRows().firstOrNull { it.id == rowId }
    }
}

private const val BULK_ADD_WRITE_GRACE_MS = 1_500L

data class DiscoveryFeedBulkAddPlan(
    val toAdd: List<Content>,
    val alreadyInLibrary: Int,
)

data class DiscoveryFeedBulkAddResult(
    val added: Int,
    val skipped: Int,
)

private data class FeedSelection(
    val ids: Set<Long> = emptySet(),
    val rowId: String? = null,
) {

    /** Drops picks no visible row shows any more (row hidden or deleted, work filtered out). */
    fun prunedTo(
        visibleRows: List<DiscoveryFeedRow>,
        cardModels: Map<String, List<ContentGridModel>>,
    ): FeedSelection {
        if (ids.isEmpty()) return this
        val shown = HashSet<Long>()
        for (row in visibleRows) {
            cardModels[row.id]?.forEach { shown += it.manga.id }
        }
        val kept = if (shown.containsAll(ids)) ids else ids.filterTo(HashSet()) { it in shown }
        if (kept.isEmpty()) return FeedSelection()
        val keptRow = rowId?.takeIf { id -> visibleRows.any { it.id == id } }
        return if (kept === ids && keptRow == rowId) this else FeedSelection(kept, keptRow)
    }
}

private data class FeedDisplayFilter(
    val hideInLibrary: Boolean,
    val libraryIds: Set<Long>,
    val selection: FeedSelection,
)
