package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.TitleSimilarity

class TitleSimilarityTest {
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
