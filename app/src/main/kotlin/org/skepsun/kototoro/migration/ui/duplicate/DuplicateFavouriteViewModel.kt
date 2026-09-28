package org.skepsun.kototoro.migration.ui.duplicate

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.FindLibraryDuplicatesUseCase
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

data class DuplicateEntry(val row: LibraryRow, val sourceBroken: Boolean)

@HiltViewModel
class DuplicateFavouriteViewModel @Inject constructor(
    private val findDuplicates: FindLibraryDuplicatesUseCase,
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val migrateUseCase: MigrateUseCase,
    private val contentDataRepository: ContentDataRepository,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    /** null = not checked yet; empty = no duplicates. */
    private val _duplicates = MutableStateFlow<List<DuplicateEntry>?>(null)
    val duplicates: StateFlow<List<DuplicateEntry>?> = _duplicates

    private val _health = MutableStateFlow(SourceHealthStatus.HEALTHY)
    val health: StateFlow<SourceHealthStatus> = _health

    val onSwitched = MutableEventFlow<Unit>()

    fun check(content: Content) {
        _duplicates.value = null
        launchJob(Dispatchers.Default) {
            val rows = findDuplicates(content)
            val unhealthy = if (rows.isEmpty()) emptySet() else {
                sourceHealthUseCase().filter { it.status.needsAttention }.mapTo(HashSet()) { it.source.name }
            }
            _duplicates.value = rows.map { DuplicateEntry(it, it.source in unhealthy) }
        }
    }

    fun loadHealth(content: Content) {
        launchJob(Dispatchers.Default) {
            _health.value = sourceHealthUseCase.forSource(content.source.name)
        }
    }

    fun switchSource(old: LibraryRow, current: Content) {
        launchLoadingJob(Dispatchers.Default) {
            val oldContent = checkNotNull(contentDataRepository.findContentById(old.id, withChapters = true))
            migrateUseCase(oldContent, current, MigrationMode.REPLACE, settings.dataFlags)
            onSwitched.call(Unit)
        }
    }

    fun disableCheck() {
        settings.isDuplicateCheckEnabled = false
    }
}
