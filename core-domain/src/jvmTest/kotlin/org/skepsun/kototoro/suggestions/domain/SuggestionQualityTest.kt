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
    fun `the rotation holds for a day and changes with each refresh`() {
        val day = 24L * 60 * 60 * 1000
        val morning = 20_000 * day + 1_000
        assertEquals(suggestionRotationSeed(morning), suggestionRotationSeed(morning + day / 2))
        assertNotEquals(suggestionRotationSeed(morning), suggestionRotationSeed(morning, refreshAt = morning))
        assertNotEquals(suggestionRotationSeed(morning, morning), suggestionRotationSeed(morning, morning + 1))
        assertNotEquals(suggestionRotationSeed(morning), suggestionRotationSeed(morning + day))
    }

    @Test
    fun `equal scores are stable across source arrival order and refresh rotates only the tail`() {
        val now = 1_800_000_000_000L
        val candidates = (1L..24L).map { SuggestionCandidate(it, SuggestionDirection.POPULAR, null, 0) }
        val profile = SuggestionProfile.of(emptyList(), now)
        fun rank(items: List<SuggestionCandidate<Long>>, seed: Long) = rankSuggestions(
            candidates = items,
            profile = profile,
            id = { it },
            source = { "source $it" },
            titles = { listOf("work $it") },
            tags = { emptyList() },
            now = now,
            seed = seed,
            limits = SuggestionLimits(stableHead = 3),
        ).map { it.item }
        val dailySeed = suggestionRotationSeed(now)
        val daily = rank(candidates, dailySeed)
        val refreshed = rank(candidates, suggestionRotationSeed(now, refreshAt = now))
        assertEquals(daily, rank(candidates.reversed(), dailySeed))
        assertEquals(daily.take(3), refreshed.take(3))
        assertNotEquals(daily.drop(3), refreshed.drop(3))
    }

    @Test
    fun `diversity lets a slightly weaker different work past a near copy of the first pick`() {
        data class W(val id: String, val score: Double, val tags: Set<String>)
        val ranked = diversifyByTags(
            listOf(
                W("isekai-1", 1.0, setOf("isekai", "fantasy", "harem")),
                W("isekai-2", 0.95, setOf("isekai", "fantasy", "harem")),
                W("sports", 0.8, setOf("sports", "school")),
                W("untagged", 0.5, emptySet()),
            ),
            score = W::score,
            tags = W::tags,
        )
        assertEquals(listOf("isekai-1", "sports", "untagged", "isekai-2"), ranked.map { it.first.id })
        assertEquals(0.475, ranked.last().second, 1e-9, "a full overlap costs half")
        assertTrue(diversifyByTags(emptyList<W>(), W::score, W::tags).isEmpty())
    }

    @Test
    fun `community gets its share and retains its reason when the source also recommends the work`() {
        fun batch(direction: SuggestionDirection, offset: Long) =
            (offset until offset + 10).mapIndexed { rank, id -> SuggestionCandidate(id, direction, null, rank) }
        val candidates = batch(SuggestionDirection.COMMUNITY, 10) + batch(SuggestionDirection.TASTE, 20) +
            batch(SuggestionDirection.POPULAR, 30) + batch(SuggestionDirection.NEW, 40) +
            batch(SuggestionDirection.EXPLORE, 50) + listOf(
                SuggestionCandidate(10L, SuggestionDirection.COMMUNITY, "Favourite", 0),
                SuggestionCandidate(10L, SuggestionDirection.TASTE, "genre.romance", 0),
            )
        val ranked = rankSuggestions(
            candidates = candidates.filterNot { it.item == 10L && it.reason == null },
            profile = SuggestionProfile.of(emptyList(), 0),
            id = { it },
            source = { "source ${it / 10}" },
            titles = { listOf("work $it") },
            tags = { emptyList() },
            now = 0,
            limits = SuggestionLimits(maxResults = 10),
        )
        assertEquals(mapOf(
            SuggestionDirection.TASTE to 4,
            SuggestionDirection.COMMUNITY to 3,
            SuggestionDirection.POPULAR to 1,
            SuggestionDirection.NEW to 1,
            SuggestionDirection.EXPLORE to 1,
        ), ranked.groupingBy { it.direction }.eachCount())
        assertEquals("Favourite", ranked.single { it.item == 10L }.reason)
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
            profile, { it.id }, { "s${it.id}" }, { listOf(it.title) }, { it.tags }, now = 2,
            quality = { if (useQuality) SuggestionQuality.fromSourceRating(it.rating) else SuggestionQuality.NEUTRAL },
        ).map { it.item.id }
        assertEquals(listOf(1L, 2L), rank(useQuality = false))
        assertEquals(listOf(2L, 1L), rank(useQuality = true))
    }
}
