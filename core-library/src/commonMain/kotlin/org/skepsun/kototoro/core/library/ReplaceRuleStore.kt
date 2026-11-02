package org.skepsun.kototoro.core.library

import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.ReplaceRuleJsonCodec
import org.skepsun.kototoro.core.replace.filterReplaceRulesForBook
import org.skepsun.kototoro.core.replace.mergeImportedReplaceRules
import org.skepsun.kototoro.core.replace.normalizeReplaceRuleIds

/**
 * Android's `ReplaceRuleRepository` for the hosts without it (Windows, iOS): the same `replace_rules` table of the
 * shared database, the same import/export JSON shape and id normalisation, without the Android-only
 * SharedPreferences migration (those hosts never had the legacy rules).
 *
 * Everything here is IO; callers keep it off the UI thread.
 */
class ReplaceRuleStore(
    private val database: MangaDatabase,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {

    private val dao get() = database.getReplaceRuleDao()

    /** The list the settings page draws: stored order, so editing a rule does not move it. */
    fun observeAll(): Flow<List<ReplaceRule>> = dao.observeAll()

    suspend fun all(): List<ReplaceRule> = dao.getAll().sortedBy { it.order }

    /** Android writes the whole list; a caller that edited one rule passes the edited list back through here. */
    suspend fun saveAll(rules: List<ReplaceRule>): List<ReplaceRule> {
        val normalized = normalizeReplaceRuleIds(rules, now()).sortedBy { it.order }
        dao.replaceAll(normalized)
        return normalized
    }

    suspend fun save(rule: ReplaceRule): List<ReplaceRule> {
        val current = all().toMutableList()
        val index = current.indexOfFirst { it.id == rule.id && rule.id != 0L }
        if (index >= 0) current[index] = rule else current += rule
        return saveAll(current)
    }

    suspend fun setEnabled(rule: ReplaceRule, enabled: Boolean): List<ReplaceRule> =
        save(rule.copy(isEnabled = enabled))

    suspend fun setAllEnabled(enabled: Boolean): List<ReplaceRule> =
        saveAll(all().map { it.copy(isEnabled = enabled) })

    suspend fun delete(id: Long): List<ReplaceRule> = saveAll(all().filterNot { it.id == id })

    suspend fun clear() = dao.replaceAll(emptyList())

    /** A file that cannot be decoded is a failed import rather than a cleared list; the count says what landed. */
    suspend fun importFromJson(raw: String): Int {
        val imported = runCatching { ReplaceRuleJsonCodec.decode(raw) }.getOrNull() ?: return 0
        val merge = mergeImportedReplaceRules(dao.getAll(), imported, now())
        dao.replaceAll(merge.rules)
        return merge.importedCount
    }

    suspend fun exportToJson(): String = ReplaceRuleJsonCodec.encode(all())

    /** The rules that apply to a book from [scopeName]/[origin], in stored order; shared filtering with Android. */
    suspend fun forBook(scopeName: String, origin: String): List<ReplaceRule> =
        filterReplaceRulesForBook(dao.getAll(), scopeName, origin)
}
