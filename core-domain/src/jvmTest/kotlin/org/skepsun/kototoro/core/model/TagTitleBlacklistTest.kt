package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagTitleBlacklistTest {

    @Test
    fun `an empty selection blocks nothing`() {
        val rules = TagTitleBlacklist(emptyList())
        assertTrue(rules.isEmpty)
        assertFalse(rules.containsTagTitle("gore"))
        assertFalse(rules.containsTagTitle(""))
    }

    @Test
    fun `matching ignores surrounding whitespace and letter case but stays exact`() {
        TagTitleBlacklist(setOf("  Dark Fantasy ")).let {
            assertFalse(it.isEmpty)
            assertTrue(it.containsTagTitle("dark fantasy"))
            assertTrue(it.containsTagTitle("DARK FANTASY  "))
            assertFalse(it.containsTagTitle("dark fantasy romance"))
        }
        assertFalse(TagTitleBlacklist(setOf("act")).containsTagTitle("action"))
    }

    @Test
    fun `a taxonomy id matches every localized and aliased source spelling`() {
        val rules = TagTitleBlacklist(setOf("setting.other-world"))
        assertTrue(rules.containsTagTitle("Isekai"))
        assertTrue(rules.containsTagTitle("异世界"))
        assertFalse(rules.containsTagTitle("Harem"))
    }

    @Test
    fun `an alias in the selection resolves to its permanent ids`() {
        val rules = TagTitleBlacklist(setOf("ecchi"))
        assertTrue(rules.containsTagTitle("轻度情色"))
        assertTrue(rules.containsTagTitle("ECC"))
    }

    @Test
    fun `unknown spellings fall back to exact raw keys`() {
        val rules = TagTitleBlacklist(setOf("custom trope", TagTitleBlacklist.rawTagKey("Another Trope")))
        assertTrue(rules.containsTagTitle("  CUSTOM TROPE  "))
        assertTrue(rules.containsTagTitle("another trope"))
        assertFalse(rules.containsTagTitle("custom tropes"))
        assertEquals("raw:another trope", TagTitleBlacklist.rawTagKey(" Another Trope "))
    }

    @Test
    fun `one work asks about all of its tags at once`() {
        val rules = TagTitleBlacklist(setOf("warning.gore"))
        assertTrue(rules.containsAnyTitle(listOf("comedy", "Gore")))
        assertFalse(rules.containsAnyTitle(listOf("comedy", "romance")))
        assertFalse(rules.containsAnyTitle(emptyList()))
    }
}
