package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.TitleMatchingRules

class TitleMatchingRulesTest {

    private val identity: (String) -> String = { it }
    private val unicodeFilter: (String) -> String = { Regex("[^\\p{L}0-9- ]").replace(it, " ") }

    @Test
    fun `folded title filtering retains letters and digits only`() {
        assertEquals("葬送的芙莉莲42", TitleMatchingRules.normalizeFoldedTitle(" 葬送的芙莉莲: 42！ "))
        assertEquals("café", TitleMatchingRules.normalizeFoldedTitle("café"))
        assertEquals("", TitleMatchingRules.normalizeFoldedTitle("  ・ "))
    }

    @Test
    fun `similarity uses supplied platform normalization`() {
        val normalize: (String) -> String = { TitleMatchingRules.normalizeFoldedTitle(it.lowercase()) }
        assertEquals(1.0, TitleMatchingRules.similarity("One Piece", "ONE-PIECE", normalize))
        assertEquals(0.0, TitleMatchingRules.similarity("", "", normalize))
        assertEquals(0.0, TitleMatchingRules.similarity("", "onepiece", normalize))
    }

    @Test
    fun `score measures substitutions insertions and deletions`() {
        assertEquals(4.0 / 7, TitleMatchingRules.similarity("kitten", "sitting", identity), 1e-9)
        assertEquals(0.5, TitleMatchingRules.similarity("ab", "a", identity))
        assertEquals(0.0, TitleMatchingRules.similarity("ab", "ba", identity))
        assertTrue(TitleMatchingRules.similarity("chainsawman", "bluelock", identity) < TitleMatchingRules.MIN_ELIGIBLE)
    }

    @Test
    fun `UTF16 edit distance counts supplementary characters as two code units`() {
        assertEquals(1.0 / 3, TitleMatchingRules.similarity("𐐀a", "a", identity), 1e-9)
    }

    @Test
    fun `alternative matching returns best score and handles empty collections`() {
        assertEquals(1.0, TitleMatchingRules.bestSimilarity(listOf("葬送的芙莉莲", "frieren"), listOf("frieren"), identity))
        assertEquals(0.0, TitleMatchingRules.bestSimilarity(emptyList(), listOf("frieren"), identity))
        assertEquals(0.0, TitleMatchingRules.bestSimilarity(listOf("frieren"), emptyList(), identity))
    }

    @Test
    fun `exact alternative stops normalization of remaining candidates`() {
        val visited = mutableListOf<String>()
        val normalize: (String) -> String = { visited.add(it); it }
        assertEquals(1.0, TitleMatchingRules.bestSimilarity(listOf("same", "unused"), listOf("same"), normalize))
        assertEquals(listOf("same", "same"), visited)
    }

    @Test
    fun `deep search removes nested brackets and unmatched suffixes`() {
        assertEquals(
            "solo leveling",
            TitleMatchingRules.cleanDeepSearchTitle("solo leveling (official [webtoon])", unicodeFilter),
        )
        assertEquals(
            "solo leveling",
            TitleMatchingRules.cleanDeepSearchTitle("solo leveling (unfinished", unicodeFilter),
        )
    }

    @Test
    fun `deep search reparses short titles backwards`() {
        assertEquals("tail", TitleMatchingRules.cleanDeepSearchTitle("(a long bracketed prefix) tail", unicodeFilter))
        assertEquals(
            "unfinished prefix tail",
            TitleMatchingRules.cleanDeepSearchTitle("(unfinished prefix tail", unicodeFilter),
        )
    }

    @Test
    fun `short latin portion retains Unicode titles through the platform filter`() {
        assertEquals("葬送的芙莉莲", TitleMatchingRules.cleanDeepSearchTitle("葬送的芙莉莲 [公式]", unicodeFilter))
        assertEquals("solo leveling", TitleMatchingRules.cleanDeepSearchTitle("solo leveling 葬送", unicodeFilter))
    }

    @Test
    fun `deep search drops Cyrillic chapter references and collapses separators`() {
        assertEquals("легенда", TitleMatchingRules.cleanDeepSearchTitle("легенда - глава 12", unicodeFilter))
        assertEquals("solo leveling", TitleMatchingRules.cleanDeepSearchTitle("solo  -  leveling", unicodeFilter))
    }

    @Test
    fun `deep search queries retain stable word ordering and remove duplicates`() {
        assertEquals(
            listOf("the eminence in shadow", "eminence shadow", "eminence", "the eminence", "the"),
            TitleMatchingRules.deepSearchQueries("the eminence in shadow"),
        )
        assertEquals(listOf("aa bb cc", "aa bb", "aa"), TitleMatchingRules.deepSearchQueries("aa bb cc"))
        assertEquals(listOf("solo"), TitleMatchingRules.deepSearchQueries("solo"))
        assertEquals(emptyList<String>(), TitleMatchingRules.deepSearchQueries("   "))
    }
}
