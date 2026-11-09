package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks committed enabled/pinned/sort changes independently of source usage. */
@Singleton
class SourceStateModifiedStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences("sync_source_state_meta", Context.MODE_PRIVATE)
    private val stateMutex = Mutex()

    /** Unknown or mismatched states have no known edit time; reading never creates a new edit. */
    fun modifiedAt(entities: List<MangaSourceEntity>): Map<String, Long> = entities.associate { entity ->
        val stored = prefs.getString(entity.source, null)
        val at = if (stored?.substringBefore('|')?.toIntOrNull() == stateHash(entity)) {
            stored.substringAfter('|', "").toLongOrNull() ?: 0L
        } else {
            0L
        }
        entity.source to at
    }

    /** Serializes DB state and its sidecar metadata across local edits and remote application. */
    suspend fun <T> withStateLock(block: suspend () -> T): T = stateMutex.withLock { block() }

    suspend fun <T> withLocalChanges(
        database: MangaDatabase,
        names: Set<String>? = null,
        block: suspend () -> T,
    ): T = withStateLock {
        suspend fun readStates(): List<MangaSourceEntity> {
            val dao = database.getSourcesDao()
            return if (names?.size == 1) {
                listOfNotNull(dao.find(names.single()))
            } else {
                dao.findAll().filter { names == null || it.source in names }
            }
        }
        val (result, changed, at) = database.withTransaction {
            val before = readStates().associateBy { it.source }
            val result = block()
            val changed = readStates().filter { entity ->
                before[entity.source]?.let { stateHash(it) } != stateHash(entity)
            }
            Triple(result, changed, System.currentTimeMillis())
        }
        // A failed or cancelled transaction must not leave metadata for a rolled-back state.
        changed.forEach { recordApplied(it.source, it.isEnabled, it.isPinned, it.sortKey, at) }
        result
    }

    /** Records a committed remote state without treating it as a new local edit. */
    fun recordApplied(source: String, enabled: Boolean, pinned: Boolean, sortKey: Int, modifiedAt: Long) {
        prefs.edit { putString(source, "${stateHash(enabled, pinned, sortKey)}|$modifiedAt") }
    }

    private fun stateHash(entity: MangaSourceEntity): Int =
        stateHash(entity.isEnabled, entity.isPinned, entity.sortKey)

    private fun stateHash(enabled: Boolean, pinned: Boolean, sortKey: Int): Int =
        "$enabled:$pinned:$sortKey".hashCode()
}
