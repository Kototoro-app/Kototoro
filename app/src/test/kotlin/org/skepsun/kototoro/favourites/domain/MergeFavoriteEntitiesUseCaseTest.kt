package org.skepsun.kototoro.favourites.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergeFavoriteEntitiesUseCaseTest {

    @Test
    fun `fuzzy title similarity ignores separator-only differences`() {
        assertEquals(
            1f,
            mergeCandidateTitleSimilarity("one piece", "onepiece"),
        )
    }

    @Test
    fun `fuzzy title similarity accepts reordered tokens`() {
        assertEquals(
            1f,
            mergeCandidateTitleSimilarity("hero academia", "academia hero"),
        )
    }

    @Test
    fun `fuzzy title similarity accepts title with extra descriptive tokens`() {
        assertTrue(
            mergeCandidateTitleSimilarity("one piece", "one piece digital colored comics") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity accepts minor spelling differences`() {
        assertTrue(
            mergeCandidateTitleSimilarity("fullmetal alchemist", "fullmetal alchmist") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity accepts common simplified and traditional title variants`() {
        assertTrue(
            mergeCandidateTitleSimilarity("我心裡危險的東西", "我心里危险的东西") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity ignores common archive and translation noise`() {
        assertTrue(
            mergeCandidateTitleSimilarity("终末的后宫（补档）", "终末的后宫") >= 0.9f,
        )
    }

    @Test
    fun `fuzzy title similarity keeps unrelated titles below minimum fuzzy threshold`() {
        assertTrue(
            mergeCandidateTitleSimilarity("one piece", "naruto") < 0.8f,
        )
    }

    @Test
    fun `fuzzy title similarity keeps shared franchise with different subtitle below tracking threshold`() {
        assertTrue(
            mergeCandidateTitleSimilarity("终末的后宫（补档）", "终末的后宫幻想版") < 0.9f,
        )
    }
}
