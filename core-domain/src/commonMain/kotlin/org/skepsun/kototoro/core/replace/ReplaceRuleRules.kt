package org.skepsun.kototoro.core.replace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android's fallback when a rule does not carry its own budget; a runaway regex must not hang the reader. */
const val DEFAULT_REPLACE_RULE_TIMEOUT_MS = 3000L

/** The text after the rules ran, plus the rules that timed out or threw (reported by name, as Android does). */
data class ReplaceRulesApplied(
    val text: String,
    val failedRuleNames: List<String> = emptyList(),
)

/** What an import changed: the merged list to store, and how many rules the file carried. */
data class ReplaceRulesImport(
    val rules: List<ReplaceRule>,
    val importedCount: Int,
)

/**
 * Gives every rule a usable primary key: the ones that already have a unique positive id keep it, the rest take the
 * next free value above [now]. Shared so Android's Room writes and Windows' writes agree on identity across hosts.
 */
fun normalizeReplaceRuleIds(rules: List<ReplaceRule>, now: Long): List<ReplaceRule> {
    val usedIds = mutableSetOf<Long>()
    var nextId = maxOf(now, rules.maxOfOrNull { it.id } ?: 0L) + 1L
    return rules.map { rule ->
        val id = if (rule.id > 0L && usedIds.add(rule.id)) {
            rule.id
        } else {
            while (!usedIds.add(nextId)) nextId++
            nextId++
            nextId - 1L
        }
        rule.copy(id = id)
    }
}

/**
 * Android's import merge: a rule whose id is already stored replaces that row, a new id is appended. Blank patterns
 * are dropped, because a rule without a pattern can never match.
 */
fun mergeImportedReplaceRules(
    existing: List<ReplaceRule>,
    imported: List<ReplaceRule>,
    now: Long,
): ReplaceRulesImport {
    val merged = existing.toMutableList()
    val indexById = merged.mapIndexed { index, rule -> rule.id to index }.toMap()
    var count = 0
    normalizeReplaceRuleIds(imported, now).filter { it.pattern.isNotBlank() }.forEach { importedRule ->
        val index = indexById[importedRule.id]
        if (index != null) merged[index] = importedRule else merged += importedRule
        count++
    }
    return ReplaceRulesImport(merged, count)
}

/** The rules that can touch either a title or the body of a book from [scopeName]/[origin], in their stored order. */
fun filterReplaceRulesForBook(rules: List<ReplaceRule>, scopeName: String, origin: String): List<ReplaceRule> {
    val scopeValues = listOf(scopeName, origin)
    return rules
        .filter { rule ->
            rule.isValid() && (
                rule.appliesTo(ReplaceRule.Scope.CONTENT, scopeValues) ||
                    rule.appliesTo(ReplaceRule.Scope.TITLE, scopeValues)
                )
        }
        .sortedBy { it.order }
}

/**
 * Runs [rules] over [text] one after another, each under its own timeout and with its own failure contained: a rule that
 * times out or throws leaves the text as the previous rule left it and is reported by name (a pattern that cannot
 * compile is a silent no-op, the way Android's `ReplaceRule.apply` swallows it). [disabledIds] is Android's per-session
 * "not this rule, just this once" set.
 */
suspend fun applyReplaceRules(
    text: String,
    rules: List<ReplaceRule>,
    disabledIds: Set<Long> = emptySet(),
): ReplaceRulesApplied {
    var result = text
    val failed = mutableListOf<String>()
    for (rule in rules) {
        if (!rule.isEnabled || rule.id in disabledIds) continue
        val timeout = if (rule.timeoutMillisecond > 0) rule.timeoutMillisecond else DEFAULT_REPLACE_RULE_TIMEOUT_MS
        val applied = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout) { runCatching { rule.apply(result) }.getOrNull() }
        }
        if (applied == null) {
            failed += rule.name.ifBlank { rule.pattern }
        } else {
            result = applied
        }
    }
    return ReplaceRulesApplied(result, failed)
}
