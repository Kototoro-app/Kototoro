package org.skepsun.kototoro.migration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.SmartMatchEngine
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

private data class FakeSource(override val name: String) : ContentSource {
    override val locale = ""
    override val contentType = ContentType.MANGA
}

private fun item(id: Long, title: String, source: ContentSource, chapters: Int = 0) = Content(
    id = id, title = title, altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
    contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
    chapters = List(chapters) { chapter(id * 1000 + it, (it + 1).toFloat()) }, source = source,
)

class SmartMatchEngineTest {
    private val a = FakeSource("A")
    private val b = FakeSource("B")
    private val c = FakeSource("C")
    private val origin = item(1, "Frieren", FakeSource("OLD"))

    private fun engine(results: Map<ContentSource, List<Content>>, failing: Set<ContentSource> = emptySet()) =
        SmartMatchEngine(
            search = { source, _ ->
                if (source in failing) error("down")
                results[source].orEmpty()
            },
            fetchDetails = { it },
        )

    @Test
    fun `first hit stops at the first eligible source`() = runTest {
        val searched = mutableListOf<String>()
        val engine = SmartMatchEngine(
            search = { source, _ ->
                searched += source.name
                if (source == b) listOf(item(20, "Frieren", b, 3)) else emptyList()
            },
            fetchDetails = { it },
        )
        val result = engine.match(origin, listOf(a, b, c), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertEquals(20L, result.best?.id)
        assertEquals(listOf("A", "B"), searched)
    }

    @Test
    fun `most chapters picks the longest eligible candidate`() = runTest {
        val engine = engine(
            mapOf(
                a to listOf(item(10, "Frieren", a, 5)),
                b to listOf(item(20, "Frieren", b, 9)),
            ),
        )
        val result = engine.match(origin, listOf(a, b), MatchMode.MOST_CHAPTERS, extraQuery = "", deepSearch = false)
        assertEquals(20L, result.best?.id)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `candidates below threshold are ignored`() = runTest {
        val engine = engine(mapOf(a to listOf(item(10, "Blue Lock", a), item(11, "Chainsaw Man", a))))
        val result = engine.match(origin, listOf(a), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertNull(result.best)
    }

    @Test
    fun `origin itself is excluded`() = runTest {
        val engine = engine(mapOf(a to listOf(origin.copy(source = a, id = 1))))
        val result = engine.match(origin, listOf(a), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertNull(result.best)
    }

    @Test
    fun `failing source is recorded and skipped`() = runTest {
        val engine = engine(mapOf(b to listOf(item(20, "Frieren", b, 1))), failing = setOf(a))
        val result = engine.match(origin, listOf(a, b), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertEquals(20L, result.best?.id)
        assertEquals(setOf("A"), result.errors.keys)
    }

    @Test
    fun `extra query is appended to the title`() = runTest {
        val queries = mutableListOf<String>()
        val engine = SmartMatchEngine(search = { _, q -> queries += q; emptyList() }, fetchDetails = { it })
        engine.match(origin, listOf(a), MatchMode.FIRST_HIT, extraQuery = "official", deepSearch = false)
        assertEquals(listOf("Frieren official"), queries)
    }

    @Test
    fun `manual search keeps results that do not resemble the original title`() = runTest {
        val engine = engine(mapOf(a to listOf(item(10, "Sousou", a), item(11, "Other", a))))
        val outcome = engine.searchSource(origin, a, query = "Sousou", minScore = 0.0)
        assertEquals(setOf(10L, 11L), outcome.candidates.map { it.content.id }.toSet())
    }

    @Test
    fun `manual search ranks by similarity to the typed query`() = runTest {
        val engine = engine(mapOf(a to listOf(item(10, "Frieren", a), item(11, "Sousou", a))))
        val outcome = engine.searchSource(origin, a, query = "Sousou", minScore = 0.0)
        assertEquals(listOf(11L, 10L), outcome.candidates.map { it.content.id })
    }

    @Test
    fun `search source returns scored candidates sorted`() = runTest {
        val engine = engine(mapOf(a to listOf(item(10, "Frieren 2", a), item(11, "Frieren", a))))
        val outcome = engine.searchSource(origin, a, query = "Frieren")
        assertEquals(listOf(11L, 10L), outcome.candidates.map { it.content.id })
        assertTrue(outcome.error == null)
    }
}
