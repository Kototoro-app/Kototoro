package org.skepsun.kototoro.core.replace

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
class ReplaceRuleRepository @Inject constructor(
    private val dao: ReplaceRuleDao,
    @ApplicationContext context: Context,
) : ReplaceRuleSource {

    private val prefs =
        context.getSharedPreferences("replace_rules", Context.MODE_PRIVATE)

    private val migrationMutex = Mutex()

    suspend fun getAll(): List<ReplaceRule> = withContext(Dispatchers.IO) {
        ensureLegacyRulesMigrated()
        dao.getAll().sortedBy { it.order }
    }

    override suspend fun getContentRules(scopeName: String, origin: String): List<ReplaceRule> =
        withContext(Dispatchers.IO) {
            ensureLegacyRulesMigrated()
            val scopeValues = listOf(scopeName, origin)
            dao.getAll()
                .filter { it.isEnabled && it.isValid() && it.appliesTo(ReplaceRule.Scope.CONTENT, scopeValues) }
                .sortedBy { it.order }
        }

    override suspend fun getTitleRules(scopeName: String, origin: String): List<ReplaceRule> =
        withContext(Dispatchers.IO) {
            ensureLegacyRulesMigrated()
            val scopeValues = listOf(scopeName, origin)
            dao.getAll()
                .filter { it.isEnabled && it.isValid() && it.appliesTo(ReplaceRule.Scope.TITLE, scopeValues) }
                .sortedBy { it.order }
        }

    suspend fun getRulesForBook(scopeName: String, origin: String): List<ReplaceRule> =
        withContext(Dispatchers.IO) {
            ensureLegacyRulesMigrated()
            filterRulesForBook(dao.getAll(), scopeName, origin)
        }

    fun filterRulesForBook(
        rules: List<ReplaceRule>,
        scopeName: String,
        origin: String,
    ): List<ReplaceRule> = filterReplaceRulesForBook(rules, scopeName, origin)

    fun observeAll(): Flow<List<ReplaceRule>> = flow {
        withContext(Dispatchers.IO) { ensureLegacyRulesMigrated() }
        emitAll(dao.observeAll().map { rules -> rules.sortedBy { it.order } })
    }

    suspend fun saveAll(rules: List<ReplaceRule>) = withContext(Dispatchers.IO) {
        ensureLegacyRulesMigrated()
        dao.replaceAll(normalizeReplaceRuleIds(rules, System.currentTimeMillis()))
    }

    suspend fun importFromJson(importJson: String): Int = withContext(Dispatchers.IO) {
        ensureLegacyRulesMigrated()
        val imported = runCatching { ReplaceRuleJsonCodec.decode(importJson) }.getOrNull()
            ?: return@withContext 0
        val merge = mergeImportedReplaceRules(dao.getAll(), imported, System.currentTimeMillis())
        dao.replaceAll(merge.rules)
        merge.importedCount
    }

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        ReplaceRuleJsonCodec.encode(getAll())
    }

    private suspend fun ensureLegacyRulesMigrated() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        migrationMutex.withLock {
            if (prefs.getBoolean(KEY_MIGRATED, false)) return
            val raw = prefs.getString(KEY_RULES, null)
            if (!raw.isNullOrBlank()) {
                val legacyRules = runCatching { ReplaceRuleJsonCodec.decode(raw) }
                    .getOrDefault(emptyList())
                    .filter { it.pattern.isNotBlank() }
                if (legacyRules.isNotEmpty()) {
                    val current = dao.getAll()
                    dao.replaceAll(normalizeReplaceRuleIds(current + legacyRules, System.currentTimeMillis()))
                }
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }

    companion object {
        private const val KEY_RULES = "rules"
        private const val KEY_MIGRATED = "room_migrated"
    }
}
