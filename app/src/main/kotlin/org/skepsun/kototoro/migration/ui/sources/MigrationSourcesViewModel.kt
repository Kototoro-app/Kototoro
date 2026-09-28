package org.skepsun.kototoro.migration.ui.sources

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.SourceHealth
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import javax.inject.Inject

data class MigrationSourcesState(
    val sources: List<SourceHealth> = emptyList(),
    val rowsById: Map<Long, LibraryRow> = emptyMap(),
    val isLoading: Boolean = true,
) {
    val attention: List<SourceHealth> get() = sources.filter { it.status.needsAttention }
    val healthy: List<SourceHealth> get() = sources.filterNot { it.status.needsAttention }
    val attentionIds: LongArray get() = attention.flatMap { it.contentIds }.toLongArray()
}

@HiltViewModel
class MigrationSourcesViewModel @Inject constructor(
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val database: MangaDatabase,
) : BaseViewModel() {

    private val _state = MutableStateFlow(MigrationSourcesState())
    val state: StateFlow<MigrationSourcesState> = _state

    init {
        refresh()
    }

    fun refresh() {
        launchLoadingJob(Dispatchers.Default) {
            val rows = database.getMigrationDao().findLibraryRows().associateBy { it.id }
            _state.value = MigrationSourcesState(sourceHealthUseCase(), rows, isLoading = false)
        }
    }
}
