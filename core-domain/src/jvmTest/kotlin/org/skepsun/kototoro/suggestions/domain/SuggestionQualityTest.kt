package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionQualityTest {

    @Test
    fun `a source rating moves the score a little, an unknown one not at all`() {
        assertEquals(1.2, SuggestionQuality.fromSourceRating(1f), 1e-9)
        assertEquals(1.0, SuggestionQuality.fromSourceRating(0.5f), 1e-9)
        assertEquals(0.84, SuggestionQuality.fromSourceRating(0.1f), 1e-6)
        assertEquals(1.0, SuggestionQuality.fromSourceRating(0f), "zero is how sources say they do not rate")
        assertEquals(1.0, SuggestionQuality.fromSourceRating(null))
        assertEquals(1.0, SuggestionQuality.fromSourceRating(Float.NaN))
    }

    @Test
    fun `enough readers' score outweighs the source, and a poor one all but removes the work`() {
        assertNull(SuggestionQuality.fromCommunity(9.0, 4.9), "too few readers")
        assertNull(SuggestionQuality.fromCommunity(Double.NaN, 5.0))
        assertNull(SuggestionQuality.fromCommunity(9.0, Double.NaN))
        assertEquals(1.0, SuggestionQuality.fromCommunity(7.0, 5.0)!!, 1e-9)
        assertEquals(1.4, SuggestionQuality.fromCommunity(9.0, 20.0)!!, 1e-9)
        assertEquals(1.6, SuggestionQuality.fromCommunity(10.0, 50.0)!!, 1e-9, "capped")
        assertEquals(0.4, SuggestionQuality.fromCommunity(5.5, 20.0)!!, 1e-9)
        assertEquals(1.4, SuggestionQuality.of(sourceRating = 0.1f, communityScore = 9.0, communityWeight = 20.0), 1e-9)
        assertEquals(1.2, SuggestionQuality.of(sourceRating = 1f, communityScore = 9.0, communityWeight = 1.0), 1e-9)
    }

    @Test
    fun `quality lets a well rated work overtake a higher source rank`() {
        data class Item(val id: Long, val title: String, val tags: List<String>, val rating: Float)
        val profile = SuggestionProfile.of(
            listOf(LibraryWork(listOf("Owned"), listOf("romance", "comedy"), isFavourite = true, percent = 1f, lastReadAt = 1)),
            now = 2,
        )
        val items = listOf(
            Item(1, "Close but poor", listOf("romance"), 0.1f),
            Item(2, "Good fit", listOf("romance"), 1f),
        )
        fun rank(useQuality: Boolean) = rankSuggestions(
            items.mapIndexed { i, it -> SuggestionCandidate(it, SuggestionDirection.POPULAR, null, i) },
            profile, { it.id }, { "s${it.id}" }, { listOf(it.title) }, { it.tags },
            quality = { if (useQuality) SuggestionQuality.fromSourceRating(it.rating) else SuggestionQuality.NEUTRAL },
        ).map { it.item.id }
        assertEquals(listOf(1L, 2L), rank(useQuality = false))
        assertEquals(listOf(2L, 1L), rank(useQuality = true))
    }
}
