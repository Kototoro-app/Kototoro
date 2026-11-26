package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection
import org.skepsun.kototoro.suggestions.domain.SuggestionLimits
import org.skepsun.kototoro.core.library.filter
import java.io.IOException
import java.nio.file.Path

class DesktopSuggestionsTest {
    @TempDir lateinit var directory: Path

    private val manga = SourceRef("MANGA_SITE", "zh", "MANGA")
    private val novels = SourceRef("NOVEL_SITE", "zh", "NOVEL")
    private val broken = SourceRef("BROKEN_SITE", "zh", "MANGA")
    private val adult = SourceRef("ADULT_SITE", "zh", "HENTAI_MANGA")

    @Test
    fun `current preferences filter cached aliases and support mixed and only language modes`() {
        val english = SourceRef("EN", "en", "MANGA")
        val items = listOf(work(1, english, "Action"), work(2, manga, "恋爱"), work(3, novels, "Horror"))
        val chinese = DesktopSuggestionSettings(language = org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.CHINESE)
        assertEquals(listOf(2L, 3L, 1L), chinese.preferences().filter(items).map { it.id })
        assertEquals(listOf(2L, 3L), chinese.copy(languageOnly = true).preferences().filter(items).map { it.id })
        assertEquals(listOf(3L, 1L), chinese.copy(tagBlacklist = setOf("Romance")).preferences().filter(items).map { it.id })
        assertEquals(listOf(3L, 1L), chinese.preferences(setOf("genre.romance")).filter(items).map { it.id })
        val restored = DesktopSuggestionSettings.from(chinese.copy(languageOnly = true).toPreferences().mapValues { it.value!! })
        assertEquals(chinese.copy(languageOnly = true), restored)
    }

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
            check(order in setOf("POPULARITY", "UPDATED")) { "what is popular in a genre comes before what was updated last: $order" }
            return when (sourceName) {
                manga.name -> if (filter?.tags?.singleOrNull()?.title in setOf("Romance", "Action")) {
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
    fun `suggestions follow the reader's taste and leave out what is on the shelves`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            // What the user reads. A source is asked for one of their tastes, Romance more often than Action.
            library.addFavourite(work(1, manga, "Romance", "Action"))
            library.addFavourite(work(2, manga, "Romance"))
            // A favourite a source lists again is not a suggestion, and its row is left alone.
            library.addFavourite(work(201, novels, "Romance", title = "已收藏的小说").copy(description = "保留的简介"))
            val fake = FakeRuntime()
            val suggestions = DesktopSuggestions(library, fake, now = { 42L }, sourceOrder = { it })

            val stored = suggestions.refresh(listOf(manga, novels, broken, adult).map(::listing))
            val ids = suggestions.suggestions().map { it.id }
            assertEquals(stored, ids.size)
            assertTrue(fake.requests.any { it.first == manga.name && it.second?.tags?.single()?.title in setOf("Romance", "Action") })
            assertTrue(fake.requests.any { it.first == novels.name && it.second == null }, "sources without the tag list unfiltered")
            assertFalse(104L in ids, "untitled works are dropped")
            assertFalse(201L in ids, "already on the shelves")
            // Taste: Romance + Action (101) beats Romance alone, which beats unrelated tags.
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
            val suggestions = DesktopSuggestions(library, FakeRuntime(), sourceOrder = { it },
                limits = SuggestionLimits(maxResults = 4))
            suggestions.refresh(listOf(manga, novels).map(::listing),
                DesktopSuggestionSettings(weighSourcesByUse = false))
            val sources = suggestions.suggestions().map { it.source.name }
            assertEquals(4, sources.size)
            assertEquals(2, sources.count { it == manga.name })
            assertEquals(2, sources.count { it == novels.name })
        }
    }

    @Test
    fun `pinned preferences seed an empty library and failed refresh keeps reasons and ages`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            var time = 42L
            val generator = DesktopSuggestions(library, FakeRuntime(), now = { time }, sourceOrder = { it })
            val settings = DesktopSuggestionSettings(tagWhitelist = setOf("Romance"))
            assertTrue(generator.refresh(listOf(listing(manga)), settings) > 0)
            val ids = generator.suggestions().map { it.id }
            val ages = library.suggestionAges()
            val reasons = library.suggestionReasons()
            assertTrue(reasons.values.any { it.second == "genre.romance" })
            time += 1_000
            generator.refresh(listOf(listing(manga)), settings)
            assertEquals(ages, library.suggestionAges(), "refresh retains the first suggestion time")
            assertThrows(IllegalStateException::class.java) {
                runBlocking { generator.refresh(listOf(listing(broken)), settings) }
            }
            assertEquals(ids.toSet(), generator.suggestions().map { it.id }.toSet())
            assertEquals(reasons, library.suggestionReasons())
        }
    }

    @Test
    fun `an empty library without pinned tags still gets a broad list`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            val generator = DesktopSuggestions(library, FakeRuntime(), now = { 42L }, sourceOrder = { it })
            assertTrue(generator.refresh(listOf(listing(novels))) > 0)
            assertTrue(library.suggestionReasons().values.none { it.first == SuggestionDirection.TASTE })
        }
    }

    @Test
    fun `page based sources advance one page when the first page is already known`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            val owned = listOf(work(1, manga, "Romance"), work(2, manga, "Romance"))
            owned.forEach { library.addFavourite(it) }
            val base = FakeRuntime()
            val offsets = mutableListOf<Int>()
            val paged = object : SourceRuntime by base {
                override suspend fun describe(sourceName: String) =
                    base.describe(sourceName).copy(pagingMode = SourcePagingMode.PAGE_INDEX)

                override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> {
                    offsets += offset
                    return when (offset) {
                        0 -> owned
                        1 -> listOf(work(101, manga, "Romance"))
                        else -> error("Skipped a page: $offset")
                    }
                }
            }
            val generator = DesktopSuggestions(library, paged, now = { 42L }, sourceOrder = { it })
            assertEquals(1, generator.refresh(listOf(listing(manga))))
            assertEquals(listOf(0, 1, 0, 1), offsets)
            assertEquals(listOf(101L), generator.suggestions().map { it.id })
        }
    }
}
