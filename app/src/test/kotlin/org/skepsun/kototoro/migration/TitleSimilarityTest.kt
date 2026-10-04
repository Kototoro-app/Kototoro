package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.TitleSimilarity
import org.skepsun.kototoro.migration.domain.TitleMatchingRules
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import java.util.Locale

class TitleSimilarityTest {
    @Test
    fun `shared scoring matches the parser UTF16 distance contract`() {
        val titles = listOf("a", "b", "ab", "ba", "aaa", "kitten", "sitting", "葬送", "𐐀a", "a𐐀")
        for (left in titles) {
            for (right in titles) {
                val expected = 1.0 - left.levenshteinDistance(right).toDouble() / maxOf(left.length, right.length)
                assertEquals(expected, TitleMatchingRules.similarity(left, right) { it }, 1e-9)
            }
        }
    }

    @Test
    fun `default locale affects deep search while invariant matching remains stable`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1.0, TitleSimilarity.similarity("ISTANBUL", "istanbul"), 1e-9)
            assertEquals("ı", TitleSimilarity.cleanDeepSearchTitle("I"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `supplementary letters retain legacy short title fallback behavior`() {
        assertEquals("", TitleSimilarity.cleanDeepSearchTitle("𐐀"))
        assertEquals("𐐨葬送的芙莉莲", TitleSimilarity.cleanDeepSearchTitle("𐐀葬送的芙莉莲"))
    }

    @Test
    fun `identical titles after normalization score one`() {
        assertEquals(1.0, TitleSimilarity.similarity("One Piece", "ONE-PIECE"), 1e-9)
    }

    @Test
    fun `empty title scores zero`() {
        assertEquals(0.0, TitleSimilarity.similarity("", "One Piece"), 1e-9)
    }

    @Test
    fun `best similarity uses alternative titles`() {
        val score = TitleSimilarity.bestSimilarity(
            listOf("葬送的芙莉莲", "Sousou no Frieren"),
            listOf("Sousou no Frieren"),
        )
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `unrelated titles fall below the eligibility threshold`() {
        assertTrue(TitleSimilarity.similarity("Chainsaw Man", "Blue Lock") < TitleSimilarity.MIN_ELIGIBLE)
    }

    @Test
    fun `deep search cleaning removes bracketed text`() {
        assertEquals("solo leveling", TitleSimilarity.cleanDeepSearchTitle("Solo Leveling (Official) [Webtoon]"))
    }

    @Test
    fun `deep search cleaning falls back to backward parsing for short titles`() {
        assertEquals("tail", TitleSimilarity.cleanDeepSearchTitle("(a long bracketed prefix) tail"))
    }

    @Test
    fun `deep search queries follow mihon order and are distinct`() {
        assertEquals(
            listOf("the eminence in shadow", "eminence shadow", "eminence", "the eminence", "the"),
            TitleSimilarity.deepSearchQueries("the eminence in shadow"),
        )
    }
}
