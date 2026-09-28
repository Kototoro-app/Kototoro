package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.extensions.PluginContentSource
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isUnresolved
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.migration.data.LibraryRow
import javax.inject.Inject

class SourceHealthUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val sourcesRepository: ContentSourcesRepository,
) {

    /** Health of every non-local source that has favourites; attention-needing first, then by count. */
    suspend operator fun invoke(now: Long = System.currentTimeMillis()): List<SourceHealth> {
        val rows = database.getMigrationDao().findLibraryRows()
        val disabled = sourcesRepository.getDisabledSources().mapTo(HashSet()) { it.name }
        return rows.groupBy { it.source }
            .mapNotNull { (name, group) -> evaluate(name, group, disabled, now) }
            .sortedWith(compareBy<SourceHealth> { !it.status.needsAttention }.thenByDescending { it.favouriteCount })
    }

    /** Verdict for a single source, used by the details screen. */
    suspend fun forSource(sourceName: String, now: Long = System.currentTimeMillis()): SourceHealthStatus {
        val rows = database.getMigrationDao().findLibraryRows().filter { it.source == sourceName }
        val disabled = sourcesRepository.getDisabledSources().mapTo(HashSet()) { it.name }
        return evaluate(sourceName, rows, disabled, now)?.status ?: SourceHealthStatus.HEALTHY
    }

    private fun evaluate(name: String, rows: List<LibraryRow>, disabled: Set<String>, now: Long): SourceHealth? {
        val source = ContentSource(name)
        if (source.isLocal) return null
        val signals = SourceSignals(
            isUnresolved = source.isUnresolved,
            isBroken = (source as? PluginContentSource)?.isBroken == true,
            isDisabled = name in disabled,
        )
        val tracks = rows.map { TrackSignal(it.trackResult, it.trackCheckTime, it.trackError) }
        val verdict = SourceHealthClassifier.classify(signals, tracks, now)
        return SourceHealth(source, verdict.status, verdict.errorSummary, rows.map { it.id })
    }
}
