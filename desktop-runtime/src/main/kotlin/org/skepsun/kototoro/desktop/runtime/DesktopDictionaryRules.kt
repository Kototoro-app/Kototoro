package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.dictionary.DictionaryRuleJsonCodec
import org.skepsun.kototoro.core.dictionary.mergeImportedDictionaryRules
import org.skepsun.kototoro.core.dictionary.normalizeDictionaryRuleOrder
import org.skepsun.kototoro.core.dictionary.sortDictionaryRules

/**
 * Android's dictionary-rule store for the Windows host: the same `dictionary_rules` table, the same JSON codec, and the
 * same relation to the bundled defaults.
 *
 * Android seeds the table from `assets/defaultData/dictRules.json` on first read; the Windows build copies that same
 * asset into its resources (`:desktop-app:prepareDesktopDictionaryRules`), so both hosts start with the two shipped
 * rules instead of an empty dictionary.
 */
class DesktopDictionaryRules(private val database: MangaDatabase) {
    private val dao get() = database.getDictionaryRuleDao()
    private val initializationMutex = Mutex()

    fun observeAll(): Flow<List<DictionaryRule>> = flow {
        withContext(Dispatchers.IO) { ensureDefaults() }
        emitAll(dao.observeAll())
    }

    suspend fun all(): List<DictionaryRule> = withContext(Dispatchers.IO) {
        ensureDefaults()
        sortDictionaryRules(dao.getAll())
    }

    suspend fun enabled(): List<DictionaryRule> = withContext(Dispatchers.IO) {
        ensureDefaults()
        dao.getEnabled()
    }

    /** Saves the list as the new order: every rule gets its position, and rules missing from it are gone. */
    suspend fun saveAll(rules: List<DictionaryRule>): List<DictionaryRule> = withContext(Dispatchers.IO) {
        ensureDefaults()
        val normalized = normalizeDictionaryRuleOrder(rules)
        dao.deleteAll()
        if (normalized.isNotEmpty()) dao.upsert(normalized)
        normalized
    }

    /** Adds a rule or replaces the one with the same name, keeping the rest of the order. */
    suspend fun save(rule: DictionaryRule): List<DictionaryRule> {
        val current = all()
        val index = current.indexOfFirst { it.name == rule.name }
        val updated = if (index >= 0) current.toMutableList().apply { this[index] = rule } else current + rule
        return saveAll(updated)
    }

    suspend fun setEnabled(rule: DictionaryRule, enabled: Boolean): List<DictionaryRule> =
        save(rule.copy(enabled = enabled))

    suspend fun delete(rule: DictionaryRule) = withContext(Dispatchers.IO) { dao.delete(rule) }

    suspend fun importFromJson(raw: String): Int = withContext(Dispatchers.IO) {
        val imported = runCatching { DictionaryRuleJsonCodec.decode(raw) }.getOrNull().orEmpty()
        if (imported.isEmpty()) return@withContext 0
        ensureDefaults()
        val merged = mergeImportedDictionaryRules(dao.getAll(), imported)
        dao.deleteAll()
        dao.upsert(merged.rules)
        merged.importedCount
    }

    suspend fun exportToJson(): String = DictionaryRuleJsonCodec.encode(all())

    private suspend fun ensureDefaults() {
        if (dao.getAll().isNotEmpty()) return
        initializationMutex.withLock {
            if (dao.getAll().isNotEmpty()) return
            val defaults = runCatching { DictionaryRuleJsonCodec.decode(readBundledDefaults()) }.getOrNull().orEmpty()
            if (defaults.isNotEmpty()) dao.upsert(defaults)
        }
    }

    /** A checkout without the generated resource (a bare test classpath) seeds nothing instead of failing. */
    private fun readBundledDefaults(): String {
        val stream = DesktopDictionaryRules::class.java.classLoader.getResourceAsStream(DEFAULT_RESOURCE)
            ?: return ""
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        const val DEFAULT_RESOURCE = "defaultData/dictRules.json"
    }
}
