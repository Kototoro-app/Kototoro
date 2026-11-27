package org.skepsun.kototoro.filter.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.util.ext.observeChanges
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import java.io.File
import javax.inject.Inject

@Reusable
class SavedFiltersRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun observeAll(source: ContentSource): Flow<List<PersistableFilter>> = getPrefs(source).observeChanges()
        .onStart { emit(null) }
        .map {
            getAll(source)
        }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    suspend fun getAll(source: ContentSource): List<PersistableFilter> = withContext(Dispatchers.Default) {
        val prefs = getPrefs(source)
        val keys = prefs.all.keys.filter { it.startsWith(FILTER_PREFIX) }
        keys.mapNotNull { key ->
            val value = prefs.getString(key, null) ?: return@mapNotNull null
            try {
                Json.decodeFromString(value)
            } catch (e: SerializationException) {
                e.printStackTraceDebug()
                null
            }
        }
    }

    suspend fun find(source: ContentSource, id: Int): PersistableFilter? = withContext(Dispatchers.Default) {
        load(source, id)
    }

    suspend fun save(
        source: ContentSource,
        name: String,
        filter: ContentListFilter,
    ): PersistableFilter = withContext(Dispatchers.Default) {
        val persistableFilter = PersistableFilter(
            name = name,
            source = source,
            filter = filter,
        )
        persist(persistableFilter)
        persistableFilter
    }

    suspend fun save(
        filter: PersistableFilter,
    ) = withContext(Dispatchers.Default) {
        persist(filter)
    }

    suspend fun rename(source: ContentSource, id: Int, newName: String) = withContext(Dispatchers.Default) {
        val filter = load(source, id) ?: return@withContext
        val newFilter = filter.copy(name = newName)
        val prefs = getPrefs(source)
        prefs.edit(commit = true) {
            remove(key(id))
            putString(key(newFilter.id), Json.encodeToString(newFilter))
        }
        newFilter
    }

    suspend fun delete(source: ContentSource, id: Int) = withContext(Dispatchers.Default) {
        val prefs = getPrefs(source)
        prefs.edit(commit = true) {
            remove(key(id))
        }
    }

    suspend fun setAutoEnabled(source: ContentSource, id: Int, enabled: Boolean) = withContext(Dispatchers.Default) {
        val filter = load(source, id) ?: return@withContext
        val newFilter = filter.copy(autoEnabled = enabled)
        val prefs = getPrefs(source)
        prefs.edit(commit = true) {
            putString(key(id), Json.encodeToString(newFilter))
        }
    }

    private fun persist(persistableFilter: PersistableFilter) {
        val prefs = getPrefs(persistableFilter.source)
        val json = Json.encodeToString(persistableFilter)
        prefs.edit(commit = true) {
            putString(key(persistableFilter.id), json)
        }
    }

    private fun load(source: ContentSource, id: Int): PersistableFilter? {
        val prefs = getPrefs(source)
        val json = prefs.getString(key(id), null) ?: return null
        return try {
            Json.decodeFromString<PersistableFilter>(json)
        } catch (e: SerializationException) {
            e.printStackTraceDebug()
            null
        }
    }

    private fun getPrefs(source: ContentSource): SharedPreferences {
        val key = source.name.replace(File.separatorChar, '$')
        return context.getSharedPreferences(key, Context.MODE_PRIVATE)
    }

    private companion object {

        const val FILTER_PREFIX = "__pf_"

        fun key(id: Int) = FILTER_PREFIX + id
    }
}
