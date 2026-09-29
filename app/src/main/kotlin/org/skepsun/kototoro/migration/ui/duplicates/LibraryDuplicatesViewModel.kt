package org.skepsun.kototoro.migration.ui.duplicates

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.migration.domain.DuplicateGrouper
import org.skepsun.kototoro.migration.domain.LibraryDuplicateGroup
import org.skepsun.kototoro.migration.domain.MergeDuplicatesUseCase
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class DuplicateGroupUi(
    val group: LibraryDuplicateGroup,
    val keepId: Long,
    val isMerging: Boolean = false,
    val error: String? = null,
)

data class LibraryDuplicatesState(
    val groups: List<DuplicateGroupUi> = emptyList(),
    val isLoading: Boolean = true,
    val isMergingAll: Boolean = false,
    val mergedCount: Int = 0,
    val confirmMergeAll: Boolean = false,
)

@HiltViewModel
class LibraryDuplicatesViewModel @Inject constructor(
    private val database: MangaDatabase,
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val mergeDuplicates: MergeDuplicatesUseCase,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    private val _state = MutableStateFlow(LibraryDuplicatesState())
    val state: StateFlow<LibraryDuplicatesState> = _state

    private var mergeAllJob: Job? = null

    init {
        launchJob(Dispatchers.Default) {
            val rows = database.getMigrationDao().findLibraryRows()
            val unhealthy = sourceHealthUseCase().filter { it.status.needsAttention }.mapTo(HashSet()) { it.source.name }
            val groups = DuplicateGrouper.group(rows, unhealthy, settings.ignoredDuplicateKeys)
            _state.value = LibraryDuplicatesState(
                groups = groups.map { DuplicateGroupUi(it, it.recommendedId) },
                isLoading = false,
            )
        }
    }

    fun selectKeep(groupKey: String, id: Long) = updateGroup(groupKey) { it.copy(keepId = id, error = null) }

    fun ignore(groupKey: String) {
        settings.ignoredDuplicateKeys = settings.ignoredDuplicateKeys + groupKey
        _state.update { s -> s.copy(groups = s.groups.filterNot { it.group.key == groupKey }) }
    }

    fun merge(groupKey: String) {
        launchJob(Dispatchers.Default) { mergeGroup(groupKey) }
    }

    fun requestMergeAll() = _state.update { it.copy(confirmMergeAll = true) }

    fun dismissMergeAll() = _state.update { it.copy(confirmMergeAll = false) }

    fun mergeAll() {
        if (mergeAllJob?.isActive == true) return
        _state.update { it.copy(confirmMergeAll = false, isMergingAll = true) }
        mergeAllJob = launchJob(Dispatchers.Default) {
            try {
                _state.value.groups.map { it.group.key }.forEach { mergeGroup(it) }
            } finally {
                _state.update { it.copy(isMergingAll = false) }
            }
        }
    }

    private suspend fun mergeGroup(groupKey: String) {
        val ui = _state.value.groups.firstOrNull { it.group.key == groupKey } ?: return
        if (ui.isMerging) return
        updateGroup(groupKey) { it.copy(isMerging = true, error = null) }
        val entries = ui.group.entries.map { it.row }
        val keep = entries.first { it.id == ui.keepId }
        runCatchingCancellable {
            mergeDuplicates(keep, entries.filterNot { it.id == keep.id })
        }.onSuccess {
            _state.update { s ->
                s.copy(groups = s.groups.filterNot { it.group.key == groupKey }, mergedCount = s.mergedCount + 1)
            }
        }.onFailure { e ->
            updateGroup(groupKey) { it.copy(isMerging = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun updateGroup(groupKey: String, transform: (DuplicateGroupUi) -> DuplicateGroupUi) =
        _state.update { s -> s.copy(groups = s.groups.map { if (it.group.key == groupKey) transform(it) else it }) }
}
