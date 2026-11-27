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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedConfigRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
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
    ) : this(
        configRepository = configRepository,
        rowLoader = rowLoader,
        settings = settings,
        contentListMapper = contentListMapper,
        contentSourcesRepository = contentSourcesRepository,
        savedFiltersRepository = savedFiltersRepository,
        marker = Unit,
    )

    internal constructor(
        configRepository: DiscoveryFeedConfigRepository,
        rowLoader: DiscoveryFeedRowLoader,
        settings: AppSettings,
    ) : this(
        configRepository = configRepository,
        rowLoader = rowLoader,
        settings = settings,
        contentListMapper = null,
        contentSourcesRepository = null,
        savedFiltersRepository = null,
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

    private val jobsLock = Any()
    private val inFlightJobs = HashMap<String, Job>()
    private var refreshAllJob: Job? = null

    val uiState: StateFlow<DiscoveryFeedUiState> = combine(
        rowsFlow,
        rowStates,
        rowCardModels,
        isRefreshing,
    ) { allRows, states, cardModels, refreshing ->
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
                    put(
                        rowId,
                        if (mapped != null && mapped.size == state.items.size) {
                            mapped
                        } else {
                            state.items.map(::fallbackGridModel)
                        },
                    )
                }
            }
        }
        DiscoveryFeedUiState(
            allRows = allRows,
            visibleRows = allRows.filterNot { it.isHidden },
            rowStates = filteredStates,
            rowCardModels = resolvedCardModels,
            isRefreshing = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DiscoveryFeedUiState(
            allRows = rowsFlow.value,
            visibleRows = rowsFlow.value.filterNot { it.isHidden },
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
