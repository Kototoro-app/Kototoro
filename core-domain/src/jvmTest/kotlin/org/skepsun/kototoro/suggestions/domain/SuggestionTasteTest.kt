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
            SuggestionProfile.of(emptyList(), now), { it.id }, { it.locale }, { listOf(it.title) }, { emptyList() },
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
	fun `a source is asked for the best of the reader's tags that it offers, not of their first ten`() {
		val exact: SuggestionTagMatcher = { a, b -> a.equals(b, ignoreCase = true) }
		val specific = (1..30).map { "niche$it" }
		val library = (1..20).map { read("Work $it", "Romance", *specific.toTypedArray()) }
		val profile = SuggestionProfile.of(library, now)
		val queries = profile.planQueries("plain", 1, listOf("POPULARITY", "NEWEST"), listOf("Romance", "Horror"), { it },
			{ false }, exact)
		assertEquals(SuggestionDirection.TASTE, queries.first().direction)
		assertEquals("Romance", queries.first().tag, "the only tag of theirs the source offers")
	}

	@Test
	fun `a gallery namespace does not make a different tag, and facts about the release are no taste`() {
		val plain = suggestionTagKeys("big breasts")
		assertEquals(setOf("raw:big breasts"), plain)
		assertEquals(plain, suggestionTagKeys("tag: Big Breasts"))
		assertEquals(plain, suggestionTagKeys("female: big  breasts"))
		assertEquals(plain, suggestionTagKeys("  Big Breasts "))
		assertEquals(suggestionTagKeys("Romance"), suggestionTagKeys("tag: romance"), "also when the catalogue knows it")
		for (fact in listOf("artist: someone", "group: a circle", "language: english", "parody: original", "character: x")) {
			assertTrue(suggestionTagKeys(fact).isEmpty(), fact)
		}
		assertEquals(setOf("raw:re:zero"), suggestionTagKeys("Re:Zero"), "a colon that is not a namespace stays")
	}

	@Test
	fun `what is liked among adult works is not asked of ordinary sources, nor scored for ordinary works`() {
		val library = (1..10).map { read("Plain $it", "plainone", "plaintwo") } +
			(1..10).map { read("Adult $it", "adultone", "adulttwo").copy(isAdult = true) }
		val profile = SuggestionProfile.of(library, now)
		val ordinary = profile.forDomain(adult = false)
		val adult = profile.forDomain(adult = true)
		assertTrue("raw:plainone" in ordinary.taste && "raw:adultone" !in ordinary.taste)
		assertTrue("raw:adultone" in adult.taste && "raw:plainone" !in adult.taste)
		assertEquals(0.0, ordinary.affinity(setOf("raw:adultone")), "no part of the ordinary taste")
		assertTrue(adult.affinity(setOf("raw:adultone")) > 0.0)
		assertTrue(ordinary.queryTags().none { it.startsWith("adult") })
		assertTrue(profile.knows(listOf("Adult 1")) && ordinary.knows(listOf("Adult 1")), "but it knows every work on the shelves")
	}

	@Test
	fun `a library of one kind, or with nothing read of the other, has one taste`() {
		val onlyOrdinary = SuggestionProfile.of((1..5).map { read("Plain $it", "plainone") }, now)
		assertTrue(onlyOrdinary.forDomain(adult = true) === onlyOrdinary, "no adult taste: ask with the whole")
		val oneKindRead = SuggestionProfile.of(
			(1..5).map { read("Plain $it", "plainone") } + read("Saved adult", "x", percent = 0f, daysAgo = 90).copy(isAdult = true),
			now,
		)
		assertTrue("raw:plainone" in oneKindRead.forDomain(adult = true).taste, "an adult work that was left says nothing: fall back")
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
	fun `ranking leaves out the shelves, merges sources, follows taste and deals the sources out in turn`() {
		data class Item(val id: Long, val source: String, val title: String, val tags: List<String>)
		val profile = SuggestionProfile.of(listOf(read("Owned", "villainess", "romance"), read("Other", "villainess")), now)
		val items = listOf(
			Item(1, "a", "Owned!", listOf("villainess")),
			Item(2, "a", "Plain", listOf("romance")),
			Item(3, "a", "Fit", listOf("villainess")),
			Item(4, "b", "FIT", listOf("romance")),
			Item(5, "b", "Unknown", emptyList()),
			Item(6, "b", "Match", listOf("villainess")),
			Item(6, "b", "Match", listOf("villainess")),
		)
		fun rank(preferred: Set<String> = emptySet()) =
			rankSuggestions(items.mapIndexed { rank, item -> SuggestionCandidate(item, SuggestionDirection.TASTE, "raw:villainess", rank) },
				profile, { it.id }, { it.source }, { listOf(it.title) }, { it.tags }, preferredSources = preferred,
				limits = SuggestionLimits(maxResults = 4))
		val ranked = rank()
		assertEquals(listOf(3L, 6L, 2L, 5L), ranked.map { it.item.id },
			"owned work gone, 3 and 4 merged, and each source's best comes up in turn")
		assertEquals(listOf(1f, .75f, .5f, .25f), ranked.map { it.relevance })
		assertEquals(listOf(6L, 4L, 2L, 5L), rank(preferred = setOf("b")).map { it.item.id },
			"a preferred source takes two places a round, and a merged work opens on the preferred one")
	}

	@Test
	fun `a source is asked for a taste and its newest list, and says what each answer is for`() {
		val exact: SuggestionTagMatcher = { a, b -> a.equals(b, ignoreCase = true) }
		val profile = SuggestionProfile.of(listOf(read("One", "Romance"), read("Two", "Romance", "Comedy")), now)
		fun plan(name: String, orders: List<String>, tags: List<String>, seed: Long = 1) =
			profile.planQueries(name, seed, orders, tags, { it }, { it == "Blocked" }, exact)
		val orders = listOf("UPDATED", "POPULARITY", "NEWEST")
		val tags = listOf("恋爱", "Horror", "Blocked", "Ecchi", "Gore")
		val plans = (0 until 60).map { plan("source$it", orders, tags) }
		assertTrue(plans.all {
			it == listOf(
				SuggestionQuery(SuggestionDirection.TASTE, "POPULARITY", "恋爱", "genre.romance"),
				SuggestionQuery<String>(SuggestionDirection.NEW, "NEWEST"),
			)
		}, "what is popular in the genre they read, under the source's own name for it, and what is new")
		assertEquals(plan("a", orders, tags), plan("a", orders, tags))
		val cold = SuggestionProfile.of(emptyList(), now).planQueries("source", 1, orders, tags, { tag -> tag }, { tag -> tag == "Blocked" }, exact)
		assertEquals(
			listOf(SuggestionQuery<String>(SuggestionDirection.POPULAR, "POPULARITY"), SuggestionQuery<String>(SuggestionDirection.NEW, "NEWEST")),
			cold, "an empty library asks for the popular and the new list and no taste",
		)
		assertEquals(listOf(SuggestionQuery<String>(SuggestionDirection.NEW, "UPDATED")), plan("bare", listOf("UPDATED"), emptyList()))
		assertEquals(listOf(SuggestionQuery<String>(SuggestionDirection.POPULAR, "ALPHABETICAL")), plan("odd", listOf("ALPHABETICAL"), emptyList()))
		assertEquals("恐怖", suggestionReasonLabel("genre.horror", "zh"))
		assertEquals("Horror", suggestionReasonLabel("genre.horror", "en"))
		assertEquals("villainess", suggestionReasonLabel("raw:villainess", "zh"))
		assertEquals(SuggestionDirection.TASTE, SuggestionDirection.of(""))
		assertEquals(SuggestionDirection.NEW, SuggestionDirection.of("NEW"))
	}

	/** Diagnostic: can sources that offer ordinary genre tags be asked for the reader's taste? Prints counts only. */
	@Test
	@EnabledIfSystemProperty(named = "suggestionEvalBackup", matches = ".+")
	fun `diagnose taste queries on a real backup`() {
		val (library, now) = SuggestionHoldout.readBackup(File(System.getProperty("suggestionEvalBackup")))
		val whole = SuggestionProfile.of(library, now)
		val offered = org.skepsun.kototoro.core.model.TagTaxonomy.tags
			.filter { it.id.startsWith("genre.") }.map { it.englishLabel }
		val exact: SuggestionTagMatcher = { a, b -> a.equals(b, ignoreCase = true) }
		println("works ${library.size}: adult ${library.count { it.isAdult }}, ordinary ${library.count { !it.isAdult }}")
		for ((name, profile) in listOf("whole" to whole, "ordinary" to whole.forDomain(false), "adult" to whole.forDomain(true))) {
			val top = profile.taste.entries.sortedByDescending { it.value }.take(10).map { it.key.substringBefore(':').substringBefore('.') }
			val found = (0 until 20).count { pick ->
				profile.pickQueryTag(offered, { it }, { false }, exact, pick / 20.0, 50) != null
			}
			println("$name: ${profile.taste.size} taste keys, top 10 by share are of kinds ${top.groupingBy { it }.eachCount()}; " +
				"a source offering ${offered.size} ordinary genres is asked for a taste in $found/20 picks")
		}
	}

	/** Diagnostic: how much of the reader's tag weight the taxonomy knows, and what stripping namespaces would add. */
	@Test
	@EnabledIfSystemProperty(named = "suggestionEvalBackup", matches = ".+")
	fun `diagnose tag coverage on a real backup`() {
		val (library, now) = SuggestionHoldout.readBackup(File(System.getProperty("suggestionEvalBackup")))
		val namespace = Regex("^(tag|female|male|mixed|other|parody|character|artist|group|language|cosplayer|category|reclass):\\s*")
		val ordinary = library.filterNot { it.contentType?.startsWith("HENTAI") == true }
		println("works ${library.size}, of ordinary content ${ordinary.size}")
		val titles = ordinary.flatMap { it.tags }
		val counts = titles.groupingBy { it }.eachCount()
		val total = counts.values.sum().toDouble()
		fun known(title: String) = org.skepsun.kototoro.core.model.TagTaxonomy.resolve(title).isNotEmpty()
		val knownNow = counts.filterKeys(::known).values.sum() / total
		val stripped = counts.entries.groupBy({ namespace.replaceFirst(it.key.trim().lowercase(), "").trim() }, { it.value }).mapValues { it.value.sum() }
		val knownAfter = stripped.filterKeys(::known).values.sum() / total
		val metadata = counts.filterKeys { namespace.find(it.trim().lowercase())?.groupValues?.get(1) in setOf("artist", "group", "language", "parody", "character", "cosplayer") }.values.sum() / total
		println("distinct tag spellings ${counts.size}, after stripping namespaces ${stripped.size}")
		println("share of tag uses the taxonomy knows: now %.1f%%, after stripping %.1f%%; namespaces that are not taste (artist, group, language, parody, character): %.1f%%".format(knownNow * 100, knownAfter * 100, metadata * 100))
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
			"plain share + rarity" to SuggestionHoldout.PlainShare,
		)) {
			val result = SuggestionHoldout.evaluate(library, now, scorer = scorer)
			println("%-24s AUC %.3f  (%d engaged, %d dropped, %d pairs)".format(name, result.auc, result.engaged, result.dropped, result.pairs))
		}
	}
}
