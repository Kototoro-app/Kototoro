package org.skepsun.kototoro.explore.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.DiscoveryFeedConfigRepository
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowLoadResult
import org.skepsun.kototoro.explore.domain.DiscoveryFeedRowLoader
import javax.inject.Inject

@HiltViewModel
class DiscoveryFeedViewModel @Inject constructor(
    private val configRepository: DiscoveryFeedConfigRepository,
    private val rowLoader: DiscoveryFeedRowLoader,
    private val settings: AppSettings,
) : BaseViewModel() {

    private val rowsFlow: StateFlow<List<DiscoveryFeedRow>> = configRepository.observeRows()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = configRepository.getRows(),
        )

    private val rowStates = MutableStateFlow<Map<String, DiscoveryFeedRowState>>(emptyMap())
    private val isRefreshing = MutableStateFlow(false)

    private val jobsLock = Any()
    private val inFlightJobs = HashMap<String, Job>()
    private var refreshAllJob: Job? = null

    val uiState: StateFlow<DiscoveryFeedUiState> = combine(
        rowsFlow,
        rowStates,
        isRefreshing,
    ) { allRows, states, refreshing ->
        val validIds = allRows.mapTo(HashSet(allRows.size)) { it.id }
        val filteredStates = if (states.keys.all { it in validIds }) {
            states
        } else {
            states.filterKeys { it in validIds }
        }
        DiscoveryFeedUiState(
            allRows = allRows,
            visibleRows = allRows.filterNot { it.isHidden },
            rowStates = filteredStates,
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
                }
        }
    }

    fun onRowVisible(rowId: String) {
        val row = findRow(rowId)?.takeUnless { it.isHidden } ?: return
        val cached = rowLoader.getCached(row)
        if (cached != null) {
            rowStates.update { current ->
                current + (rowId to DiscoveryFeedRowState.Loaded(cached.items, cached.fetchedAtMs))
            }
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
        }
        return updated
    }

    fun reorderRows(orderedIds: List<String>): List<DiscoveryFeedRow> {
        return configRepository.reorderRows(orderedIds)
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
