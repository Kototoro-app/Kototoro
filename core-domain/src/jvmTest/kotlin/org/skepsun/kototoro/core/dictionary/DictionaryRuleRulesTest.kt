package org.skepsun.kototoro.core.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DictionaryRuleRulesTest {

    private fun rule(name: String, sortNumber: Int = 0, enabled: Boolean = true) = DictionaryRule(
        name = name,
        urlRule = "https://example.com/$name",
        enabled = enabled,
        sortNumber = sortNumber,
    )

    @Test
    fun `sorts by sort number and then by name`() {
        val rules = listOf(rule("b", 1), rule("a", 1), rule("c", 0))

        assertEquals(listOf("c", "a", "b"), sortDictionaryRules(rules).map { it.name })
    }

    @Test
    fun `saving hands every rule its position`() {
        val normalized = normalizeDictionaryRuleOrder(listOf(rule("a", 7), rule("b", 3), rule("c", 9)))

        assertEquals(listOf(0, 1, 2), normalized.map { it.sortNumber })
        assertEquals(listOf("a", "b", "c"), normalized.map { it.name })
    }

    @Test
    fun `import replaces the same name and adds new ones`() {
        val merged = mergeImportedDictionaryRules(
            existing = listOf(rule("百度汉语", 0), rule("海词英文", 1)),
            imported = listOf(rule("海词英文", 5).copy(showRule = "tag.body@all"), rule("本地词典", 2)),
        )

        assertEquals(2, merged.importedCount)
        // The document's own sortNumber decides where a rule lands, exactly like Android: the replaced rule keeps the
        // imported 5, so it moves behind the rule the file numbered 2.
        assertEquals(listOf("百度汉语", "本地词典", "海词英文"), merged.rules.map { it.name })
        assertEquals("tag.body@all", merged.rules.last().showRule)
        assertEquals(listOf(0, 1, 2), merged.rules.map { it.sortNumber })
    }

    @Test
    fun `import keeps the stored enabled flag of untouched rules`() {
        val merged = mergeImportedDictionaryRules(
            existing = listOf(rule("off", 0, enabled = false), rule("on", 1)),
            imported = listOf(rule("new", 0)),
        )

        // Same-numbered rules fall back to the name, so the imported one sorts first.
        assertEquals(listOf("new", "off", "on"), merged.rules.map { it.name })
        assertEquals(listOf(true, false, true), merged.rules.map { it.enabled })
    }

    @Test
    fun `an empty import changes nothing and reports zero`() {
        val existing = listOf(rule("a", 0), rule("b", 1))

        val merged = mergeImportedDictionaryRules(existing, emptyList())

        assertEquals(0, merged.importedCount)
        assertEquals(listOf("a", "b"), merged.rules.map { it.name })
    }
}
