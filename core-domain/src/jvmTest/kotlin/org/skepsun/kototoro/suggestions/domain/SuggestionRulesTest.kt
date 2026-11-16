package org.skepsun.kototoro.suggestions.domain

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionRulesTest {

	private val exact: SuggestionTagMatcher = { a, b -> a.equals(b, ignoreCase = true) }

    @Test
    fun `excluded genres match translated aliases and canonical ids`() {
        val translated = SuggestionTagBlacklist(setOf("Romance"), ::suggestionTagsMatch)
        assertTrue(translated.containsTitle("恋爱"))
        assertTrue(translated.containsAny(listOf("Action", "爱情")))
        assertTrue(SuggestionTagBlacklist(setOf("genre.romance"), exact).containsTitle("恋爱"))
        assertFalse(translated.containsTitle("Horror"))
    }

    @Test
    fun `tag preferences accept Chinese punctuation and pasted lines`() {
        assertEquals(setOf("恋爱", "恐怖", "悬疑"), parseSuggestionTags(" 恋爱，恐怖；\n悬疑 "))
    }

	@Test
	fun `portable matching uses Android mean length and strict threshold`() {
		assertTrue(suggestionTagsMatch("ACTION", "action"))
		assertTrue(suggestionTagsMatch("Romance", "romanse"))
		assertTrue(suggestionTagsMatch("奇幻", "奇幻"))
		assertFalse(suggestionTagsMatch("Action", "Horror"))
		assertFalse(suggestionTagsMatch("abc", "ab"), "distance equals the 0.4 threshold")
		assertFalse(suggestionTagsMatch("", ""))
	}

	@Test
	fun `seed tags put the whitelist first then the most frequent tags`() {
		assertEquals(listOf("b", "a", "c"), listOf("a", "b", "b", "c", "a", "b").mostFrequent(3))
		assertEquals(listOf("a", "b"), listOf("a", "b").mostFrequent(5), "ties keep first appearance")
		assertEquals(listOf("Fantasy", "Romance", "Action"),
			suggestionSeedTags(listOf("Fantasy"), listOf("Romance", "Romance", "Action", "Fantasy"), limit = 2))
	}

	@Test
	fun `relevance favours tags early in the seed and is zero without tags`() {
		val seed = listOf("Romance", "Action", "Comedy")
		val both = suggestionRelevance(listOf("Romance", "Action"), seed, exact)
		val first = suggestionRelevance(listOf("romance"), seed, exact)
		val last = suggestionRelevance(listOf("Comedy"), seed, exact)
		assertEquals(1f, first)
		assertTrue(both > last && first > last)
		assertEquals(0f, suggestionRelevance(listOf("Horror"), seed, exact))
		assertEquals(0f, suggestionRelevance(emptyList(), seed, exact), "no NaN for tagless works")
	}

	@Test
	fun `sources get the preferred order, a matching unblocked tag and a cleaned list`() {
		assertEquals("POPULARITY", pickSuggestionSortOrder(listOf("UPDATED", "POPULARITY")))
		assertEquals("POPULARITY_MONTH", pickSuggestionSortOrder(listOf("POPULARITY", "POPULARITY_MONTH")))
		assertEquals("ALPHABETICAL", pickSuggestionSortOrder(listOf("ALPHABETICAL")))
		assertEquals("UPDATED", pickSuggestionSortOrder(emptyList()))
		val blacklist = SuggestionTagBlacklist(setOf("Romance"), exact)
		assertEquals("Action", pickSuggestionTag(listOf("Romance", "Action"), listOf("Romance", "Action"), { it },
			{ blacklist.containsTitle(it) }, exact))
		assertNull(pickSuggestionTag(listOf("Horror"), listOf("Action"), { it }, { false }, exact))
		assertTrue(blacklist.containsAny(listOf("Drama", "romance")))
		assertFalse(SuggestionTagBlacklist(emptySet(), exact).containsAny(listOf("x")))
		val items = (1..30).map { "t$it" } + listOf(" ", "bad")
		val limits = SuggestionLimits(maxSourceResults = 5)
		assertEquals(listOf("t2", "t3", "t4", "t5", "t6"), cleanSuggestionList(items, { it }, { it != "t1" }, { it == "bad" }, limits),
			"the source's own order when nothing is known")
		assertEquals(listOf("t30", "t20", "t2", "t3", "t4"), cleanSuggestionList(items, { it }, { it != "t1" }, { it == "bad" }, limits) {
			if (it == "t30") 2.0 else if (it == "t20") 1.0 else 0.0
		}, "the best fits survive the cap")
	}

	@Test
	fun `tag lists parse like Android's comma separated settings`() {
		assertEquals(setOf("Romance", "Action"), parseSuggestionTags(" Romance , Action, ,"))
		assertEquals(emptySet<String>(), parseSuggestionTags(null))
		assertEquals(emptySet<String>(), parseSuggestionTags(" , "))
	}

	@Test
	fun `collection survives failing sources`() = runBlocking {
		val results = collectSourceResults(listOf(1, 2, 3)) { if (it == 2) error("down") else listOf(it, it * 10) }
		assertEquals(setOf(1, 10, 3, 30), results.toSet())
	}
}
