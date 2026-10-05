package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.suggestions.domain.SuggestionLimits
import java.io.IOException
import java.nio.file.Path

class DesktopSuggestionsTest {
    @TempDir lateinit var directory: Path

    private val manga = SourceRef("MANGA_SITE", "zh", "MANGA")
    private val novels = SourceRef("NOVEL_SITE", "zh", "NOVEL")
    private val broken = SourceRef("BROKEN_SITE", "zh", "MANGA")
    private val adult = SourceRef("ADULT_SITE", "zh", "HENTAI_MANGA")

    private fun tag(title: String, source: SourceRef) = SourceTag(title, title.lowercase(), source)

    private fun work(id: Long, source: SourceRef, vararg tags: String, title: String = "作品 $id") = SourceContent(
        id, title, emptySet(), "/w$id", "https://fixture.invalid/w$id", 0f, null, null,
        tags.map { tag(it, source) }.toSet(), null, emptySet(), source,
    )

    private inner class FakeRuntime : SourceRuntime {
        val requests = mutableListOf<Pair<String, SourceFilter?>>()
        override suspend fun getSources() = listOf(manga, novels, broken, adult)
        override suspend fun describe(sourceName: String) = SourceDescriptor(
            SourceRef(sourceName, "zh", "MANGA"), setOf("POPULARITY", "UPDATED"), "POPULARITY",
            SourceFilterCapabilities(true, false, true, true, false, false, false, false), SourcePagingMode.OFFSET,
        )
        override suspend fun getFilterOptions(sourceName: String) = SourceFilterOptions(
            availableTags = if (sourceName == manga.name) setOf(tag("Romance", manga), tag("Action", manga)) else emptySet(),
            tagGroups = emptyList(), availableStates = emptySet(), availableContentRating = emptySet(),
            availableContentTypes = emptySet(), availableDemographics = emptySet(), availableLocales = emptySet(),
        )
        override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> {
            requests += sourceName to filter
            check(order == "UPDATED") { "Android prefers UPDATED: $order" }
            return when (sourceName) {
                manga.name -> if (filter?.tags?.singleOrNull()?.title == "Romance") {
                    listOf(work(101, manga, "Romance", "Action"), work(102, manga, "Comedy"), work(103, manga, "Romance"),
                        work(104, manga, title = " "))
                } else emptyList()
                novels.name -> listOf(work(201, novels, "Romance"), work(202, novels, "Horror"))
                adult.name -> listOf(work(301, adult, "Romance"))
                else -> throw IOException("offline")
            }
        }
        override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) = content
        override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?) = emptyList<SourcePage>()
        override suspend fun getPageUrl(page: SourcePage) = page.url
        override suspend fun getRelated(content: SourceContent) = emptyList<SourceContent>()
    }

    private fun listing(source: SourceRef) = SourceListing(source, source.name, SourceEcosystem.MIHON, true)

    @Test
    fun `suggestions follow Android's seed tags, relevance and balanced ranking`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            // Seed: what the user reads. Romance dominates, so it is the tag sent to sources.
            library.addFavourite(work(1, manga, "Romance", "Action"))
            library.addFavourite(work(2, manga, "Romance"))
            // A stored favourite returned by a source keeps its richer row.
            library.addFavourite(work(201, novels, "Romance", title = "已收藏的小说").copy(description = "保留的简介"))
            val fake = FakeRuntime()
            val suggestions = DesktopSuggestions(library, fake, now = { 42L }, shuffle = {}, sourceOrder = { it })

            DesktopRuntime.open(directory.resolve("empty")).use { empty ->
                assertEquals(0, DesktopSuggestions(DesktopLibrary(empty.database) { null }, fake)
                    .refresh(listOf(listing(manga))), "no reading seed, no suggestions")
            }

            val stored = suggestions.refresh(listOf(manga, novels, broken, adult).map(::listing))
            val ids = suggestions.suggestions().map { it.id }
            assertEquals(stored, ids.size)
            assertTrue(fake.requests.any { it.first == manga.name && it.second?.tags?.single()?.title == "Romance" })
            assertTrue(fake.requests.any { it.first == novels.name && it.second == null }, "sources without the tag list unfiltered")
            assertFalse(104L in ids, "untitled works are dropped")
            // Relevance: Romance + Action (101) beats Romance alone, which beats unrelated tags.
            assertTrue(ids.indexOf(101L) < ids.indexOf(103L))
            assertTrue(ids.indexOf(103L) < ids.indexOf(102L) || ids.indexOf(103L) < ids.indexOf(202L))
            assertEquals("保留的简介", library.find(201)?.description)

            val excluded = suggestions.refresh(listOf(manga, novels, adult).map(::listing),
                DesktopSuggestionSettings(excludeNsfw = true, excludedSources = setOf(novels.name)))
            val afterExclusion = suggestions.suggestions().map { it.id }
            assertEquals(excluded, afterExclusion.size)
            assertTrue(afterExclusion.none { it >= 200 }, "excluded and adult sources contribute nothing: $afterExclusion")
        }
    }

    @Test
    fun `one source cannot fill the list`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            library.addFavourite(work(1, manga, "Romance"))
            val suggestions = DesktopSuggestions(library, FakeRuntime(), shuffle = {}, sourceOrder = { it },
                limits = SuggestionLimits(maxResults = 4, maxResultsPerSource = 2))
            suggestions.refresh(listOf(manga, novels).map(::listing))
            val sources = suggestions.suggestions().map { it.source.name }
            assertEquals(4, sources.size)
            assertEquals(2, sources.count { it == manga.name })
            assertEquals(2, sources.count { it == novels.name })
        }
    }
}
