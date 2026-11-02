package org.skepsun.kototoro.core.dictionary

/** Android's display order: the stored `sortNumber`, then the name, so equal numbers stay deterministic. */
fun sortDictionaryRules(rules: List<DictionaryRule>): List<DictionaryRule> =
    rules.sortedWith(compareBy<DictionaryRule> { rule -> rule.sortNumber }.thenBy { it.name })

/** Saving hands every rule its position in the list, which is what the page shows after a reorder. */
fun normalizeDictionaryRuleOrder(rules: List<DictionaryRule>): List<DictionaryRule> =
    rules.mapIndexed { index, rule -> rule.copy(sortNumber = index) }

/** The merged list (to write) and how many rules the document carried (what the toast reports). */
data class DictionaryRulesImport(
    val rules: List<DictionaryRule>,
    val importedCount: Int,
)

/**
 * Android's import: a rule whose `name` already exists is replaced, a new name is added, and the result is renumbered
 * from the top so the file's own `sortNumber` never fights the stored order.
 */
fun mergeImportedDictionaryRules(
    existing: List<DictionaryRule>,
    imported: List<DictionaryRule>,
): DictionaryRulesImport {
    if (imported.isEmpty()) return DictionaryRulesImport(existing, 0)
    val current = existing.associateBy { it.name }.toMutableMap()
    imported.forEach { rule -> current[rule.name] = rule }
    return DictionaryRulesImport(normalizeDictionaryRuleOrder(sortDictionaryRules(current.values.toList())), imported.size)
}
