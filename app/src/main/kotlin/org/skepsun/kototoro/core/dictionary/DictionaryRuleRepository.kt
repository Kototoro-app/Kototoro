package org.skepsun.kototoro.core.dictionary

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryRuleRepository @Inject constructor(
    private val dao: DictionaryRuleDao,
    @ApplicationContext private val context: Context,
) {

    private val initializationMutex = Mutex()

    fun observeAll(): Flow<List<DictionaryRule>> = flow {
        withContext(Dispatchers.IO) { ensureDefaults() }
        emitAll(dao.observeAll().map { sorted(it) })
    }

    suspend fun getAll(): List<DictionaryRule> = withContext(Dispatchers.IO) {
        ensureDefaults()
        sorted(dao.getAll())
    }

    suspend fun getEnabled(): List<DictionaryRule> = withContext(Dispatchers.IO) {
        ensureDefaults()
        sorted(dao.getEnabled())
    }

    suspend fun saveAll(rules: List<DictionaryRule>) = withContext(Dispatchers.IO) {
        ensureDefaults()
        dao.deleteAll()
        if (rules.isNotEmpty()) dao.upsert(normalizeDictionaryRuleOrder(rules))
    }

    suspend fun importFromJson(raw: String): Int = withContext(Dispatchers.IO) {
        val imported = runCatching { DictionaryRuleJsonCodec.decode(raw) }.getOrNull().orEmpty()
        if (imported.isEmpty()) return@withContext 0
        ensureDefaults()
        val merged = mergeImportedDictionaryRules(dao.getAll(), imported)
        dao.deleteAll()
        dao.upsert(merged.rules)
        merged.importedCount
    }

    suspend fun exportToJson(): String = DictionaryRuleJsonCodec.encode(getAll())

    private suspend fun ensureDefaults() {
        if (dao.getAll().isNotEmpty()) return
        initializationMutex.withLock {
            if (dao.getAll().isNotEmpty()) return
            val raw = runCatching {
                context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
            val defaults = raw?.let { runCatching { DictionaryRuleJsonCodec.decode(it) }.getOrNull() }.orEmpty()
            if (defaults.isNotEmpty()) dao.upsert(defaults)
        }
    }

    private fun sorted(rules: List<DictionaryRule>): List<DictionaryRule> = sortDictionaryRules(rules)

    private companion object {
        const val DEFAULT_ASSET = "defaultData/dictRules.json"
    }
}
