package org.skepsun.kototoro.explore.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.observeChanges
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores Explore Source Discovery Feed row configurations as JSON in [SharedPreferences].
 *
 * Default configuration is [emptyList]. No Room database tables or migrations are used.
 */
@Singleton
class DiscoveryFeedConfigRepository internal constructor(
    private val prefs: SharedPreferences,
    private val json: Json = DEFAULT_JSON,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    @Inject
    constructor(settings: AppSettings) : this(
        prefs = settings.prefs,
        json = DEFAULT_JSON,
        idGenerator = { UUID.randomUUID().toString() },
    )

    private val lock = Any()

    fun observeRows(): Flow<List<DiscoveryFeedRow>> = flow {
        emit(getRows())
        prefs.observeChanges().collect { changedKey ->
            if (changedKey == null || changedKey == KEY_ROWS) {
                emit(getRows())
            }
        }
    }.distinctUntilChanged()

    fun getRows(): List<DiscoveryFeedRow> = synchronized(lock) {
        decodeRows(prefs.getString(KEY_ROWS, null))
    }

    fun addRow(
        sourceName: String,
        displayName: String,
        savedFilterId: Int? = null,
        query: String? = null,
        isHidden: Boolean = false,
    ): DiscoveryFeedRow = synchronized(lock) {
        val normalizedSource = sourceName.trim()
        require(normalizedSource.isNotEmpty()) { "sourceName must not be blank" }
        val normalizedDisplayName = displayName.trim().ifEmpty { normalizedSource }
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }

        val existing = decodeRows(prefs.getString(KEY_ROWS, null))
        val newRow = DiscoveryFeedRow(
            id = nextUniqueId(existing),
            sourceName = normalizedSource,
            savedFilterId = savedFilterId,
            query = normalizedQuery,
            displayName = normalizedDisplayName,
            sortOrder = existing.size,
            isHidden = isHidden,
        )
        persistRows(existing + newRow)
        newRow
    }

    fun updateRow(
        rowId: String,
        displayName: String,
        savedFilterId: Int? = null,
        query: String? = null,
        sourceName: String? = null,
    ): DiscoveryFeedRow? = synchronized(lock) {
        val existing = decodeRows(prefs.getString(KEY_ROWS, null))
        val index = existing.indexOfFirst { it.id == rowId }
        if (index < 0) {
            return null
        }
        val current = existing[index]
        val updatedSource = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: current.sourceName
        val updatedDisplayName = displayName.trim().ifEmpty { updatedSource }
        val updatedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val updatedRow = current.copy(
            sourceName = updatedSource,
            savedFilterId = savedFilterId,
            query = updatedQuery,
            displayName = updatedDisplayName,
        )
        val updatedList = existing.toMutableList().apply {
            this[index] = updatedRow
        }
        persistRows(updatedList)
        updatedRow
    }

    fun reorderRows(orderedIds: List<String>): List<DiscoveryFeedRow> = synchronized(lock) {
        val existing = decodeRows(prefs.getString(KEY_ROWS, null))
        if (existing.isEmpty()) {
            return emptyList()
        }
        val byId = existing.associateBy { it.id }
        val seen = LinkedHashSet<String>(existing.size)
        val reordered = ArrayList<DiscoveryFeedRow>(existing.size)
        for (id in orderedIds) {
            val row = byId[id] ?: continue
            if (seen.add(id)) {
                reordered += row
            }
        }
        for (row in existing) {
            if (seen.add(row.id)) {
                reordered += row
            }
        }
        val normalized = reordered.mapIndexed { index, row ->
            row.copy(sortOrder = index)
        }
        persistRows(normalized)
        normalized
    }

    fun toggleRowHidden(rowId: String): DiscoveryFeedRow? = synchronized(lock) {
        val existing = decodeRows(prefs.getString(KEY_ROWS, null))
        val index = existing.indexOfFirst { it.id == rowId }
        if (index < 0) {
            return null
        }
        val updatedRow = existing[index].copy(isHidden = !existing[index].isHidden)
        val updatedList = existing.toMutableList().apply {
            this[index] = updatedRow
        }
        persistRows(updatedList)
        updatedRow
    }

    fun deleteRow(rowId: String): Boolean = synchronized(lock) {
        val existing = decodeRows(prefs.getString(KEY_ROWS, null))
        val remaining = existing.filterNot { it.id == rowId }
        if (remaining.size == existing.size) {
            return false
        }
        val normalized = remaining.mapIndexed { index, row ->
            row.copy(sortOrder = index)
        }
        persistRows(normalized)
        true
    }

    internal fun encodeRows(rows: List<DiscoveryFeedRow>): String {
        return json.encodeToString(ROW_LIST_SERIALIZER, rows)
    }

    internal fun decodeRows(raw: String?): List<DiscoveryFeedRow> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            json.decodeFromString(ROW_LIST_SERIALIZER, raw)
                .sortedBy { it.sortOrder }
        } catch (e: SerializationException) {
            e.printStackTraceDebug()
            emptyList()
        } catch (e: IllegalArgumentException) {
            e.printStackTraceDebug()
            emptyList()
        }
    }

    private fun persistRows(rows: List<DiscoveryFeedRow>) {
        prefs.edit {
            putString(KEY_ROWS, encodeRows(rows))
        }
    }

    private fun nextUniqueId(existing: List<DiscoveryFeedRow>): String {
        val existingIds = existing.mapTo(HashSet(existing.size)) { it.id }
        var candidate = idGenerator()
        while (candidate.isBlank() || candidate in existingIds) {
            candidate = UUID.randomUUID().toString()
        }
        return candidate
    }

    companion object {
        const val KEY_ROWS = AppSettings.KEY_DISCOVERY_FEED_ROWS

        private val ROW_LIST_SERIALIZER = ListSerializer(DiscoveryFeedRow.serializer())

        internal val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
