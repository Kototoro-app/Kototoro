package org.skepsun.kototoro.stats.domain

import androidx.collection.LongSparseArray
import androidx.collection.set
import androidx.room.withTransaction
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.RetainedLifecycleCoroutineScope
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.stats.data.StatsEntity
import javax.inject.Inject

@ViewModelScoped
class StatsCollector @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
    lifecycle: ViewModelLifecycle,
) {

    private val viewModelScope = RetainedLifecycleCoroutineScope(lifecycle)
    private val stats = LongSparseArray<Entry>(1)

    @Synchronized
    fun onStateChanged(mangaId: Long, state: ReaderState) {
        if (!settings.isStatsEnabled) {
            return
        }
        val now = System.currentTimeMillis()
        val entry = stats[mangaId]
        if (entry == null) {
            stats[mangaId] = Entry(
                state = state,
                stats = StatsEntity(
                    mangaId = mangaId,
                    startedAt = now,
                    duration = 0,
                    pages = 0,
                ),
            )
            return
        }
        val pagesDelta = if (entry.state.page != state.page || entry.state.chapterId != state.chapterId) 1 else 0
        val newEntry = entry.copy(
            state = state,
            stats = StatsEntity(
                mangaId = mangaId,
                startedAt = entry.stats.startedAt,
                duration = now - entry.stats.startedAt,
                pages = entry.stats.pages + pagesDelta,
            ),
        )
        stats[mangaId] = newEntry
        commit(newEntry.stats)
    }

    @Synchronized
    fun onPause(mangaId: Long) {
        stats[mangaId]?.let { entry ->
            commit(entry.stats.copy(duration = (System.currentTimeMillis() - entry.stats.startedAt).coerceAtLeast(0)))
        }
        stats.remove(mangaId)
    }

    private fun commit(entity: StatsEntity) {
        viewModelScope.launch(Dispatchers.Default) {
            runCatchingCancellable {
                db.withTransaction {
                    val dao = db.getStatsDao()
                    val previous = dao.find(entity.mangaId, entity.startedAt)
                    dao.upsert(entity.copy(duration = maxOf(entity.duration, previous?.duration ?: 0),
                        pages = maxOf(entity.pages, previous?.pages ?: 0)))
                }
            }.onFailure { e ->
                e.printStackTraceDebug()
            }
        }
    }

    private data class Entry(
        val state: ReaderState,
        val stats: StatsEntity,
    )
}
