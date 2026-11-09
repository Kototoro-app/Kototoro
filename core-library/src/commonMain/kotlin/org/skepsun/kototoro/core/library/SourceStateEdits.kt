package org.skepsun.kototoro.core.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity

/**
 * Android's `SourceStateModifiedStore` for hosts that keep switches and pins in the `sources` table: when each
 * enabled/pinned/sort state was last changed, which decides whose state wins in Google Drive sync. A time stays
 * valid only while the row still has the state it was recorded for, so unrecorded changes read as unknown (0).
 * [load] and [save] persist the `source -> "hash|time"` entries in the host's storage.
 */
class SourceStateEdits(
    private val load: () -> Map<String, String>,
    private val save: (Map<String, String>) -> Unit,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var entries: Map<String, String>? = null

    suspend fun modifiedAt(states: List<MangaSourceEntity>): Map<String, Long> = mutex.withLock {
        val stored = current()
        states.associate { state ->
            val entry = stored[state.source]
            state.source to if (entry?.substringBefore('|')?.toIntOrNull() == stateHash(state)) {
                entry.substringAfter('|', "").toLongOrNull() ?: 0L
            } else 0L
        }
    }

    /** Records committed states, each with its own edit time (remote states keep the time they were made). */
    suspend fun record(states: List<Pair<MangaSourceEntity, Long>>) {
        if (states.isEmpty()) return
        mutex.withLock {
            val updated = current() + states.map { (state, at) -> state.source to "${stateHash(state)}|$at" }
            save(updated)
            entries = updated
        }
    }

    /** Runs a local edit of the `sources` table and stamps every row whose state it changed. */
    suspend fun <T> localChange(database: MangaDatabase, block: suspend () -> T): T {
        val dao = database.getSourcesDao()
        val before = dao.findAll().associate { it.source to stateHash(it) }
        val result = block()
        val at = clock()
        record(dao.findAll().filter { before[it.source] != stateHash(it) }.map { it to at })
        return result
    }

    private fun current() = entries ?: load().also { entries = it }

    private fun stateHash(state: MangaSourceEntity) = "${state.isEnabled}:${state.isPinned}:${state.sortKey}".hashCode()
}
