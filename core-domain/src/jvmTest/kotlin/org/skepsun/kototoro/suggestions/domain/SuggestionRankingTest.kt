package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionRankingTest {

    private data class Item(val id: Long, val source: String)

    private val profile = SuggestionProfile.of(emptyList(), 0)

    private fun rank(
        candidates: List<SuggestionCandidate<Item>>,
        maxResults: Int,
        preferred: Set<String> = emptySet(),
    ) = rankSuggestions(
        candidates = candidates,
        profile = profile,
        id = { it.id },
        source = { it.source },
        titles = { listOf("work ${it.id}") },
        tags = { emptyList() },
        preferredSources = preferred,
        limits = SuggestionLimits(maxResults = maxResults),
    )

    private fun list(source: String, count: Int, firstId: Long, direction: SuggestionDirection = SuggestionDirection.POPULAR) =
        (0 until count).map { SuggestionCandidate(Item(firstId + it, source), direction, null, it) }

    private fun bySource(ranked: List<RankedSuggestion<Item>>) = ranked.groupingBy { it.item.source }.eachCount()

    @Test
    fun `the same answers give the same list whatever order the sources answered in`() {
        val candidates = list("a", 30, 100) + list("b", 30, 200) + list("c", 30, 300)
        val ranked = rank(candidates, 40).map { it.item.id }
        assertEquals(ranked, rank(candidates.reversed(), 40).map { it.item.id })
        assertEquals(ranked, rank(candidates.shuffled(kotlin.random.Random(1)), 40).map { it.item.id })
    }

    @Test
    fun `every source gets the same share however long its list is`() {
        val ranked = rank(list("long", 80, 100) + list("b", 8, 200) + list("c", 8, 300), 12)
        assertEquals(mapOf("long" to 4, "b" to 4, "c" to 4), bySource(ranked))
    }

    @Test
    fun `a long list fills what the shorter ones leave`() {
        val ranked = rank(list("long", 80, 100) + list("b", 3, 200) + list("c", 3, 300), 20)
        assertEquals(mapOf("long" to 14, "b" to 3, "c" to 3), bySource(ranked))
    }

    @Test
    fun `a preferred source takes two places a round`() {
        val ranked = rank(list("a", 20, 100) + list("b", 20, 200), 9, preferred = setOf("a"))
        assertEquals(mapOf("a" to 6, "b" to 3), bySource(ranked))
        assertEquals(100L, ranked.first().item.id, "and goes first")
    }

    @Test
    fun `every fourth place is a community pick, which keeps its reason when a source also lists the work`() {
        val candidates = list("a", 20, 100) + list("b", 20, 200) +
            list("c", 10, 10, SuggestionDirection.COMMUNITY).filterNot { it.item.id == 10L } +
            listOf(
                SuggestionCandidate(Item(10, "c"), SuggestionDirection.COMMUNITY, "Favourite", 0),
                SuggestionCandidate(Item(10, "a"), SuggestionDirection.POPULAR, null, 0),
            )
        val ranked = rank(candidates, 12)
        assertEquals(
            List(12) { index -> (index + 1) % 4 == 0 },
            ranked.map { it.direction == SuggestionDirection.COMMUNITY },
        )
        assertEquals("Favourite", ranked.single { it.item.id == 10L }.reason)
    }

    @Test
    fun `community picks fill the list when the sources have run out`() {
        val ranked = rank(list("a", 2, 100) + list("c", 6, 10, SuggestionDirection.COMMUNITY), 8)
        assertEquals(8, ranked.size)
        assertEquals(6, ranked.count { it.direction == SuggestionDirection.COMMUNITY })
    }

    @Test
    fun `every direction the sources answered for is represented, not only the one that scores highest`() {
        val sources = (1..10).map { "s$it" }
        val candidates = sources.flatMapIndexed { index, source ->
            val base = index * 1000L
            list(source, 20, base, SuggestionDirection.TASTE) + list(source, 20, base + 100, SuggestionDirection.POPULAR) +
                list(source, 20, base + 200, SuggestionDirection.NEW)
        }
        val ranked = rank(candidates, 160)
        val counts = ranked.groupingBy { it.direction }.eachCount()
        assertEquals(160, ranked.size)
        for (direction in listOf(SuggestionDirection.TASTE, SuggestionDirection.POPULAR, SuggestionDirection.NEW)) {
            assertTrue((counts[direction] ?: 0) >= 40, "$direction has ${counts[direction]} of 160: $counts")
        }
    }

    @Test
    fun `a source the reader reads from gets more places than one they never opened a work of`() {
        val library = (1..100).map { LibraryWork(listOf("Read $it"), emptyList(), id = it.toLong(), source = "mine") } +
            LibraryWork(listOf("Rare"), emptyList(), id = 1000L, source = "rare")
        val profile = SuggestionProfile.of(library, 0)
        assertEquals(3.0, profile.sourceWeight("mine"))
        assertEquals(1.0, profile.sourceWeight("rare"))
        assertEquals(0.5, profile.sourceWeight("new"))
        val ranked = rankSuggestions(
            list("mine", 60, 1000) + list("rare", 60, 2000) + list("new", 60, 3000), profile,
            { it.id }, { it.source }, { listOf("work ${it.id}") }, { emptyList() }, limits = SuggestionLimits(maxResults = 45),
        )
        val share = ranked.groupingBy { it.item.source }.eachCount()
        assertEquals(45, ranked.size)
        assertTrue(share.getValue("mine") > share.getValue("rare") && share.getValue("rare") > share.getValue("new"), "$share")
        assertTrue(share.getValue("new") > 0, "an unused source still gets a place now and then: $share")
    }

    @Test
    fun `with weighing off every source gets the same places however much the reader reads from it`() {
        val library = (1..100).map { LibraryWork(listOf("Read $it"), emptyList(), id = it.toLong(), source = "mine") }
        val profile = SuggestionProfile.of(library, 0)
        val ranked = rankSuggestions(
            list("mine", 60, 1000) + list("new", 60, 3000), profile,
            { it.id }, { it.source }, { listOf("work ${it.id}") }, { emptyList() },
            limits = SuggestionLimits(maxResults = 40), weighSources = false,
        )
        assertEquals(mapOf("mine" to 20, "new" to 20), ranked.groupingBy { it.item.source }.eachCount())
        assertEquals(listOf("mine" to 100), profile.sourceUsage())
        assertTrue(profile.weighsSources)
    }

    @Test
    fun `a small library weighs every source the same`() {
        val profile = SuggestionProfile.of((1..5).map { LibraryWork(listOf("W $it"), emptyList(), source = "a") }, 0)
        assertEquals(1.0, profile.sourceWeight("a"))
        assertEquals(1.0, profile.sourceWeight("b"))
    }

    @Test
    fun `relevance encodes the order`() {
        val ranked = rank(list("a", 5, 100) + list("b", 5, 200), 10)
        assertEquals(ranked.map { it.relevance }.sortedDescending(), ranked.map { it.relevance })
        assertEquals(1f, ranked.first().relevance)
    }
}
