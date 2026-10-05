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
		assertEquals("UPDATED", pickSuggestionSortOrder(listOf("POPULARITY", "UPDATED")))
		assertEquals("ALPHABETICAL", pickSuggestionSortOrder(listOf("ALPHABETICAL")))
		assertEquals("UPDATED", pickSuggestionSortOrder(emptyList()))
		val blacklist = SuggestionTagBlacklist(setOf("Romance"), exact)
		assertEquals("Action", pickSuggestionTag(listOf("Romance", "Action"), listOf("Romance", "Action"), { it },
			{ blacklist.containsTitle(it) }, exact))
		assertNull(pickSuggestionTag(listOf("Horror"), listOf("Action"), { it }, { false }, exact))
		assertTrue(blacklist.containsAny(listOf("Drama", "romance")))
		assertFalse(SuggestionTagBlacklist(emptySet(), exact).containsAny(listOf("x")))
		val cleaned = cleanSuggestionList((1..30).map { "t$it" } + listOf(" ", "bad"), { it }, { it != "t1" },
			{ it == "bad" }, SuggestionLimits(maxSourceResults = 5), shuffle = {})
		assertEquals(listOf("t2", "t3", "t4", "t5", "t6"), cleaned)
	}

	@Test
	fun `ranking dedupes, balances preferred sources and re-encodes the rank`() {
		data class Item(val id: Long, val source: String, val score: Float)
		val items = listOf(Item(1, "a", .9f), Item(1, "a", .8f), Item(2, "a", .7f), Item(3, "a", .6f),
			Item(4, "b", .5f), Item(5, "c", .4f))
		val ranked = rankSuggestions(items, { it.score }, { it.id }, { it.source }, preferredSources = setOf("c"),
			limits = SuggestionLimits(maxResults = 4, maxResultsPerSource = 2))
		assertEquals(listOf(5L, 1L, 4L, 2L), ranked.map { it.item.id })
		assertEquals(listOf(1f, .75f, .5f, .25f), ranked.map { it.relevance })
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
