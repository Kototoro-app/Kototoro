package org.skepsun.kototoro.search.domain

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchRulesTest {

    private fun item(title: String, alt: String? = null, authors: List<String> = emptyList(), tags: List<String> = emptyList()) =
        SearchCandidate(title, alt, authors, tags)

    @Test
    fun titleSearchNeedsASearchableSource() {
        assertNull(SearchRules.plan(SearchKind.TITLE, "one", null, supportsSearch = false, supportsAuthorSearch = true, knownTags = emptyList()))
        assertEquals("one", SearchRules.plan(SearchKind.SIMPLE, "one", null, true, false, emptyList())?.query)
    }

    @Test
    fun authorSearchFallsBackToTitleSearch() {
        assertEquals("Oda", SearchRules.plan(SearchKind.AUTHOR, "Oda", null, true, true, emptyList())?.author)
        val fallback = SearchRules.plan(SearchKind.AUTHOR, "Oda", null, true, false, emptyList())
        assertEquals("Oda", fallback?.query)
        assertNull(fallback?.author)
        assertNull(SearchRules.plan(SearchKind.AUTHOR, "Oda", null, false, false, emptyList()))
    }

    @Test
    fun tagSearchOnlyUsesTagsTheSourceHas() {
        val plan = SearchRules.plan(SearchKind.TAG, "Action, -Horror, Missing", null, true, true, listOf("action", "Horror"))
        assertEquals(setOf("action"), plan?.tags)
        assertEquals(setOf("Horror"), plan?.tagsExclude)
        assertNull(SearchRules.plan(SearchKind.TAG, "Missing", null, true, true, listOf("action")))
    }

    @Test
    fun advancedSearchCombinesTheFieldsTheSourceSupports() {
        val advanced = AdvancedSearchParams(query = "q", title = "", tags = "Action", author = "Oda")
        val plan = SearchRules.plan(SearchKind.ADVANCED, "q", advanced, true, false, listOf("Action"))
        assertEquals("q", plan?.query)
        assertNull(plan?.author)
        assertEquals(setOf("Action"), plan?.tags)
    }

    @Test
    fun titleResultsKeepNearMatchesOnly() {
        assertTrue(SearchRules.accepts(item("One Piece"), SearchKind.TITLE, "one piece", null))
        assertTrue(SearchRules.accepts(item("x", alt = "One Piece"), SearchKind.TITLE, "one piece", null))
        assertFalse(SearchRules.accepts(item("Naruto"), SearchKind.TITLE, "one piece", null))
        assertTrue(SearchRules.accepts(item("Naruto"), SearchKind.SIMPLE, "one piece", null))
    }

    @Test
    fun authorResultsKeepWorksWithoutAuthors() {
        assertTrue(SearchRules.accepts(item("a"), SearchKind.AUTHOR, "Oda", null))
        assertTrue(SearchRules.accepts(item("a", authors = listOf("Eiichiro Oda")), SearchKind.AUTHOR, "oda", null))
        assertFalse(SearchRules.accepts(item("a", authors = listOf("Kishimoto")), SearchKind.AUTHOR, "oda", null))
    }

    @Test
    fun advancedResultsNeedEveryAskedField() {
        val advanced = AdvancedSearchParams(tags = "Action, -Horror")
        assertTrue(SearchRules.accepts(item("a", tags = listOf("action")), SearchKind.ADVANCED, "", advanced))
        assertFalse(SearchRules.accepts(item("a", tags = listOf("action", "horror")), SearchKind.ADVANCED, "", advanced))
        assertFalse(SearchRules.accepts(item("a", tags = listOf("comedy")), SearchKind.ADVANCED, "", advanced))
    }

    @Test
    fun closerTitlesComeFirstAndTiesKeepTheirOrder() {
        val items = listOf(item("Naruto"), item("One Piece"), item("One Pieces"), item("One Piece"))
        val sorted = SearchRules.sortByRelevance(items, SearchKind.TITLE, "One Piece", null) { it }
        assertEquals(listOf("One Piece", "One Piece", "One Pieces", "Naruto"), sorted.map { it.title })
        assertTrue(sorted[0] === items[1] && sorted[1] === items[3])
    }

    @Test
    fun tagSearchRanksByWantedMinusUnwantedTags() {
        val a = item("a", tags = listOf("Action", "Horror"))
        val b = item("b", tags = listOf("Action"))
        val sorted = SearchRules.sortByRelevance(listOf(a, b), SearchKind.TAG, "Action, -Horror", null) { it }
        assertEquals(listOf("b", "a"), sorted.map { it.title })
    }

    @Test
    fun editDistanceMatchesTheParsersLibrary() {
        assertEquals(3, SearchRules.levenshteinDistance("kitten", "sitting"))
        assertEquals(0, SearchRules.levenshteinDistance("same", "same"))
        assertEquals(4, SearchRules.levenshteinDistance("", "abcd"))
    }

    @Test
    fun parallelSearchAsksEverySourceWithinTheLimit() = runTest {
        val running = AtomicInteger()
        var peak = 0
        val answered = mutableListOf<Int>()
        searchSourcesInParallel(
            sources = (1..10).toList(),
            parallelism = 3,
            search = { source ->
                peak = maxOf(peak, running.incrementAndGet())
                kotlinx.coroutines.yield()
                running.decrementAndGet()
                source * 2
            },
            onResult = { _, result -> answered += result },
        )
        assertEquals((1..10).map { it * 2 }.toSet(), answered.toSet())
        assertTrue(peak <= 3)
    }

    @Test
    fun contentKindsFollowTheContentTypeName() {
        assertEquals(SearchContentKind.NOVEL, searchContentKindOf("HENTAI_NOVEL"))
        assertEquals(SearchContentKind.VIDEO, searchContentKindOf("VIDEO"))
        assertEquals(SearchContentKind.MANGA, searchContentKindOf("MANGA"))
        assertEquals(SearchContentKind.MANGA, searchContentKindOf(null))
    }
}
