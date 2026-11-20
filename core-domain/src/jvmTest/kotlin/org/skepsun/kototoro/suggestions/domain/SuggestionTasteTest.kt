package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File

class SuggestionTasteTest {

    @Test
    fun `language preference defaults to system and recognises regional Chinese sources`() {
        assertEquals(SuggestionLanguage.SYSTEM, SuggestionLanguage.of(null))
        assertTrue(SuggestionLanguage.SYSTEM.accepts("zh-TW", "zh-CN"))
        assertTrue(SuggestionLanguage.CHINESE.accepts("zh_Hans"))
        assertFalse(SuggestionLanguage.CHINESE.accepts("en"))
        assertFalse(SuggestionLanguage.CHINESE.accepts(null))
        assertTrue(SuggestionLanguage.ALL.accepts(null))
    }

    @Test
    fun `mixed ranking prefers the chosen language but keeps other languages and chooses matching duplicate`() {
        data class Item(val id: Long, val title: String, val locale: String)
        val items = listOf(Item(1, "Same story", "en"), Item(2, "Same story", "zh"), Item(3, "Other story", "en"))
        val result = rankSuggestions(items.mapIndexed { i, it -> SuggestionCandidate(it, SuggestionDirection.POPULAR, null, i) },
            SuggestionProfile.of(emptyList(), now), { it.id }, { it.locale }, { listOf(it.title) }, { emptyList() }, now,
            languagePriority = { it.locale == "zh" })
        assertEquals(listOf(2L, 3L), result.map { it.item.id })
    }

	private val day = 24L * 60 * 60 * 1000
	private val now = 1_000 * day

	private fun read(title: String, vararg tags: String, percent: Float = 0.8f, daysAgo: Long = 1, favourite: Boolean = false) =
		LibraryWork(listOf(title), tags.toList(), favourite, percent, chapters = 40, lastReadAt = now - daysAgo * day)

	@Test
	fun `one tag has one key in any language and format tags have none`() {
		assertEquals(suggestionTagKeys("Romance"), suggestionTagKeys("恋爱"))
		assertEquals(suggestionTagKeys("romance"), suggestionTagKeys(" ROMANCE "))
		assertEquals(setOf("raw:no such tag anywhere"), suggestionTagKeys("No Such Tag Anywhere"))
		assertTrue(suggestionTagKeys("").isEmpty())
		assertEquals("rezero2", suggestionTitleKey("Re:Zero 2"))
	}

	@Test
	fun `reading counts for more than opening and a dropped work for nothing`() {
		val finished = read("a", "x")
		val opened = read("b", "x", percent = 0.2f)
		val dropped = read("c", "x", percent = 0.05f, daysAgo = 40)
		val keptAnyway = dropped.copy(isFavourite = true)
		val saved = LibraryWork(listOf("d"), listOf("x"), isFavourite = true)
		fun weight(work: LibraryWork, recency: Int? = 0) = SuggestionProfile.weightOf(work, recency, now)
		assertTrue(weight(finished) > weight(opened))
		assertEquals(0.0, weight(dropped))
		assertTrue(weight(keptAnyway) > 0.0)
		assertTrue(weight(saved, recency = null) in 0.0..weight(opened), "an intention says less than anything read")
		assertTrue(weight(finished) > weight(finished.copy(lastReadAt = now - 400 * day)), "old reading fades")
		assertTrue(weight(finished, recency = 0) > weight(finished, recency = 100))
	}

	@Test
	fun `taste is each tag's share and knows every name on the shelves`() {
		val profile = SuggestionProfile.of(
			listOf(read("One", "A", "B"), read("Two", "A").copy(titles = listOf("Two", "Zwei")), read("Gone", "C", percent = 0f, daysAgo = 60)),
			now,
		)
		assertEquals(1.0, profile.taste.getValue("raw:a"), 1e-9)
		assertEquals(0.5, profile.taste.getValue("raw:b"), 1e-9)
		assertFalse("raw:c" in profile.taste, "a dropped work is no taste")
		assertTrue(profile.knows(listOf("ZWEI!")))
		assertTrue(profile.knows(listOf("gone")), "but it is still not suggested again")
		assertFalse(profile.knows(listOf("Three")))
		val renamed = SuggestionProfile.of(listOf(read("My own title", "A").copy(id = 9)), now)
		assertTrue(renamed.knows(9, listOf("As the source lists it")), "the same entry under a title the reader changed")
		assertFalse(renamed.knows(10, listOf("As the source lists it")))
		assertEquals(listOf("A", "B"), profile.queryTags())
	}

	@Test
	fun `pinned tags lead the queries and count as the strongest taste`() {
		val profile = SuggestionProfile.of(listOf(read("One", "A"), read("Two", "A", "B")), now, pinnedTags = listOf("Z"))
		assertEquals(listOf("Z", "A", "B"), profile.queryTags())
		assertEquals(profile.taste.getValue("raw:a"), profile.taste.getValue("raw:z"))
		assertFalse(SuggestionProfile.of(emptyList(), now, listOf("Z")).isEmpty)
	}

	@Test
	fun `sources are asked for different tastes, by meaning before spelling`() {
		val exact: SuggestionTagMatcher = { a, b -> a.equals(b, ignoreCase = true) }
		val profile = SuggestionProfile.of(
			listOf(read("One", "Romance", "Odd Tag"), read("Two", "Romance", "Comedy"), read("Three", "Romance"), read("Four", "Comedy")),
			now,
		)
		fun ask(offered: List<String>, pick: Double = 0.0, blocked: String? = null) =
			profile.pickQueryTag(offered, { it }, { it == blocked }, exact, pick)
		assertEquals("恋爱", ask(listOf("搞笑", "恋爱")), "the strongest taste, in the source's own language")
		assertEquals("搞笑", ask(listOf("搞笑", "恋爱"), blocked = "恋爱"))
		assertEquals("odd tag", ask(listOf("odd tag")), "an unknown tag is still found by its spelling")
		assertEquals(null, ask(listOf("Horror")))
		assertEquals(null, ask(emptyList()))
		val leads = (0 until 200).map { ask(listOf("Romance", "Comedy", "Odd Tag"), pick = it / 200.0) }
		assertEquals(setOf("Romance", "Comedy", "Odd Tag"), leads.toSet())
		assertTrue(leads.count { it == "Romance" } > leads.count { it == "Comedy" }, "in proportion to the taste")
		assertTrue(leads.count { it == "Comedy" } > leads.count { it == "Odd Tag" })
		val picks = listOf("a", "b", "c", "d", "e", "f").map { suggestionQueryPick(it, seed = 1) }
		assertEquals(picks, listOf("a", "b", "c", "d", "e", "f").map { suggestionQueryPick(it, seed = 1) })
		assertTrue(picks.all { it in 0.0..1.0 } && picks.distinct().size == picks.size)
	}

	@Test
	fun `affinity counts a rare shared tag for more and does not reward tag count`() {
		val profile = SuggestionProfile.of(listOf(read("One", "common", "rare"), read("Two", "common", "rare")), now)
		val candidates = listOf(setOf("raw:common"), setOf("raw:common"), setOf("raw:common", "raw:rare"), setOf("raw:rare"))
		val rarity = TagRarity.of(candidates)
		assertTrue(rarity("raw:rare") > rarity("raw:common"))
		assertTrue(profile.affinity(setOf("raw:rare"), rarity) > profile.affinity(setOf("raw:common"), rarity))
		assertTrue(profile.affinity(setOf("raw:rare"), rarity) >
			profile.affinity(setOf("raw:rare", "raw:p", "raw:q", "raw:r"), rarity), "padding with tags does not help")
		assertEquals(0.0, profile.affinity(emptySet(), rarity))
	}

	@Test
	fun `works under a shared name are one candidate`() {
		data class Item(val id: Int, val titles: List<String>)
		val groups = mergeByTitle(
			listOf(Item(1, listOf("Solo")), Item(2, listOf("Alpha", "アルファ")), Item(3, listOf("アルファ", "Арфа")),
				Item(4, listOf("арфа!")), Item(5, listOf(""))),
		) { it.titles }
		assertEquals(listOf(listOf(1), listOf(2, 3, 4), listOf(5)), groups.map { group -> group.map { it.id } })
	}

	@Test
	fun `rotation keeps the head and the same seed, and mostly follows the scores`() {
		val sorted = (0 until 60).toList()
		val score = { index: Int -> 1.0 / (1 + index) }
		val rotated = rotateRanked(sorted, score, seed = 7, stable = 8)
		assertEquals(sorted.take(8), rotated.take(8))
		assertEquals(sorted.toSet(), rotated.toSet())
		assertEquals(rotated, rotateRanked(sorted, score, seed = 7, stable = 8))
		assertTrue(rotated != rotateRanked(sorted, score, seed = 8, stable = 8))
		assertTrue(rotated.subList(8, 20).average() < rotated.subList(48, 60).average(), "better matches still come first")
		assertEquals(listOf(1, 2), rotateRanked(listOf(1, 2), { 1.0 }, seed = 1, stable = 8))
	}

	@Test
	fun `ranking leaves out the shelves, merges sources, follows taste and lets stale suggestions sink`() {
		data class Item(val id: Long, val source: String, val title: String, val tags: List<String>)
		val profile = SuggestionProfile.of(listOf(read("Owned", "villainess", "romance"), read("Other", "villainess")), now)
		val items = listOf(
			Item(1, "a", "Owned!", listOf("villainess")),
			Item(2, "a", "Plain", listOf("romance")),
			Item(3, "a", "Fit", listOf("villainess")),
			Item(4, "b", "FIT", listOf("romance")),
			Item(5, "b", "Unknown", emptyList()),
			Item(6, "b", "Stale", listOf("villainess")),
			Item(6, "b", "Stale", listOf("villainess")),
		)
		fun rank(limits: SuggestionLimits = SuggestionLimits(maxResults = 4), preferred: Set<String> = emptySet()) =
			rankSuggestions(items.mapIndexed { rank, item -> SuggestionCandidate(item, SuggestionDirection.TASTE, "raw:villainess", rank) },
				profile, { it.id }, { it.source }, { listOf(it.title) }, { it.tags }, now = now,
				firstSuggestedAt = { if (it.id == 6L) now - 10 * day else null }, preferredSources = preferred, limits = limits)
		val ranked = rank()
		assertEquals(listOf(3L, 2L, 6L, 5L), ranked.map { it.item.id },
			"owned work gone, 3 and 4 merged, and ten days unopened puts 6 below a weaker match")
		assertEquals(listOf(1f, .75f, .5f, .25f), ranked.map { it.relevance })
		assertEquals(listOf(4L, 6L, 5L, 2L), rank(preferred = setOf("b")).map { it.item.id },
			"preferred sources first, and a merged work opens on the preferred one")
		assertEquals(listOf(3L, 6L), rank(SuggestionLimits(maxResults = 4, maxResultsPerSource = 1)).map { it.item.id })
	}

	@Test
	fun `a source is asked for a taste and one wider list, and says what each answer is for`() {
		val exact: SuggestionTagMatcher = { a, b -> a.equals(b, ignoreCase = true) }
		val profile = SuggestionProfile.of(listOf(read("One", "Romance"), read("Two", "Romance", "Comedy")), now)
		fun plan(name: String, orders: List<String>, tags: List<String>, seed: Long = 1) =
			profile.planQueries(name, seed, orders, tags, { it }, { it == "Blocked" }, exact)
		val orders = listOf("UPDATED", "POPULARITY", "NEWEST")
		val tags = listOf("恋爱", "Horror", "Blocked", "Ecchi", "Gore")
		val plans = (0 until 60).map { plan("source$it", orders, tags) }
		assertTrue(plans.all { it.size == 2 })
		assertTrue(plans.all { it[0] == SuggestionQuery(SuggestionDirection.TASTE, "POPULARITY", "恋爱", "genre.romance") },
			"what is popular in the genre they read, under the source's own name for it")
		assertEquals(
			setOf(
				SuggestionQuery(SuggestionDirection.POPULAR, "POPULARITY"),
				SuggestionQuery(SuggestionDirection.NEW, "NEWEST"),
				SuggestionQuery(SuggestionDirection.EXPLORE, "POPULARITY", "Horror", "genre.horror"),
			),
			plans.map { it[1] }.toSet(),
			"between them the sources cover every direction, and never explore what is blocked, adult or a warning",
		)
		assertEquals(plan("a", orders, tags), plan("a", orders, tags))
		val cold = (0 until 60).map {
			SuggestionProfile.of(emptyList(), now).planQueries("source$it", 1, orders, tags, { tag -> tag }, { tag -> tag == "Blocked" }, exact)
		}
		assertTrue(cold.all { it.size == 1 && it[0].direction != SuggestionDirection.TASTE },
			"an empty library asks every source for one wider list and no taste")
		assertEquals(setOf(SuggestionDirection.POPULAR, SuggestionDirection.NEW, SuggestionDirection.EXPLORE),
			cold.map { it[0].direction }.toSet())
		assertEquals(listOf(SuggestionQuery<String>(SuggestionDirection.NEW, "UPDATED")), plan("bare", listOf("UPDATED"), emptyList()))
		assertEquals(listOf(SuggestionQuery<String>(SuggestionDirection.POPULAR, "ALPHABETICAL")), plan("odd", listOf("ALPHABETICAL"), emptyList()))
		assertEquals("恐怖", suggestionReasonLabel("genre.horror", "zh"))
		assertEquals("Horror", suggestionReasonLabel("genre.horror", "en"))
		assertEquals("villainess", suggestionReasonLabel("raw:villainess", "zh"))
		assertEquals(SuggestionDirection.TASTE, SuggestionDirection.of(""))
		assertEquals(SuggestionDirection.NEW, SuggestionDirection.of("NEW"))
	}

	@Test
	fun `directions are dealt into one list in proportion and each keeps its own order`() {
		data class Item(val id: Long, val source: String, val tags: List<String> = emptyList())
		val profile = SuggestionProfile.of(listOf(read("One", "liked"), read("Two", "liked")), now)
		fun list(direction: SuggestionDirection, base: Long, source: String, tags: List<String> = emptyList()) =
			(0L until 30L).map { SuggestionCandidate(Item(base + it, source, tags), direction, null, it.toInt()) }
		val candidates = list(SuggestionDirection.POPULAR, 200, "p") + list(SuggestionDirection.NEW, 300, "n") +
			list(SuggestionDirection.EXPLORE, 400, "e") + list(SuggestionDirection.TASTE, 100, "t", listOf("liked")) +
			// The same work on a wider list as well: it stays what it was found as by taste
			SuggestionCandidate(Item(100, "t", listOf("liked")), SuggestionDirection.POPULAR, null, 0)
		val ranked = rankSuggestions(candidates, profile, { it.id }, { it.source }, { listOf("title ${it.id}") }, { it.tags },
			now = now, limits = SuggestionLimits(maxResults = 20, maxResultsPerSource = 30, stableHead = 100))
		assertEquals(mapOf(SuggestionDirection.TASTE to 10, SuggestionDirection.POPULAR to 4, SuggestionDirection.NEW to 4,
			SuggestionDirection.EXPLORE to 2), ranked.groupingBy { it.direction }.eachCount())
		assertEquals(SuggestionDirection.TASTE, ranked.first().direction)
		assertEquals(setOf(SuggestionDirection.TASTE, SuggestionDirection.POPULAR, SuggestionDirection.NEW, SuggestionDirection.EXPLORE),
			ranked.take(8).map { it.direction }.toSet(), "the mix shows every direction early")
		assertEquals(listOf(200L, 201L, 202L, 203L), ranked.filter { it.direction == SuggestionDirection.POPULAR }.map { it.item.id },
			"a wider list keeps the order the source gave it")
		assertEquals(ranked.map { it.relevance }.sortedDescending(), ranked.map { it.relevance })
		val short = rankSuggestions(list(SuggestionDirection.NEW, 300, "n"), profile, { it.id }, { it.source },
			{ listOf("title ${it.id}") }, { it.tags }, now = now, limits = SuggestionLimits(maxResults = 5))
		assertEquals(5, short.size, "a direction with nothing to offer leaves its share to the others")
	}

	@Test
	fun `hold-out evaluation counts how often a hit outranks a miss`() {
		val library = (0 until 40).map { index ->
			if (index % 2 == 0) read("hit$index", "liked", "common") else read("miss$index", "common", "other$index", percent = 0.02f, daysAgo = 60)
		}
		val perfect = SuggestionHoldout.evaluate(library, now) { _, hidden, _ -> hidden.map { if (it.percent!! > 0.5f) 1.0 else 0.0 } }
		val blind = SuggestionHoldout.evaluate(library, now) { _, hidden, _ -> hidden.map { 0.0 } }
		assertEquals(1.0, perfect.auc)
		assertEquals(0.5, blind.auc)
		assertEquals(20, perfect.engaged)
		assertEquals(20, perfect.dropped)
		assertEquals(1.0, SuggestionHoldout.evaluate(library, now, scorer = SuggestionHoldout.ProfileWithRarity).auc)
	}

	/** `./gradlew :core-domain:jvmTest --tests '*SuggestionTasteTest*' -PsuggestionEvalBackup=/path/to/kototoro.bk.zip` */
	@Test
	@EnabledIfSystemProperty(named = "suggestionEvalBackup", matches = ".+")
	fun `hold-out evaluation of a real backup`() {
		val (library, now) = SuggestionHoldout.readBackup(File(System.getProperty("suggestionEvalBackup")))
		println("library: ${library.size} works, ${library.count { it.lastReadAt > 0L }} read, ${library.count { it.tags.isNotEmpty() }} tagged")
		for ((name, scorer) in listOf(
			"legacy seed tags" to SuggestionHoldout.Legacy,
			"taste profile" to SuggestionHoldout.Profile,
			"taste profile + rarity" to SuggestionHoldout.ProfileWithRarity,
		)) {
			val result = SuggestionHoldout.evaluate(library, now, scorer = scorer)
			println("%-24s AUC %.3f  (%d engaged, %d dropped, %d pairs)".format(name, result.auc, result.engaged, result.dropped, result.pairs))
		}
	}
}
