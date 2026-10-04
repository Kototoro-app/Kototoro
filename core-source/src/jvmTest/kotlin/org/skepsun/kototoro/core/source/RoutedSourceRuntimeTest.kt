package org.skepsun.kototoro.core.source

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RoutedSourceRuntimeTest {
    private class Recording(val prefix: String, val names: List<String>) : SourceRuntime {
        val calls = mutableListOf<String>()
        private fun ref(name: String) = SourceRef(name, "en", "MANGA")
        override suspend fun getSources() = names.map(::ref)
        override suspend fun describe(sourceName: String): SourceDescriptor {
            calls += "describe:$sourceName"
            return SourceDescriptor(ref(sourceName), setOf("POPULARITY"), "POPULARITY",
                SourceFilterCapabilities(false, false, true, false, false, false, false, false), SourcePagingMode.OFFSET)
        }
        override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions = TODO()
        override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> {
            calls += "list:$sourceName:$offset"
            return emptyList()
        }
        override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent {
            calls += "details:${content.source.name}"
            return content
        }
        override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> {
            calls += "pages:${chapter.source.name}"
            return emptyList()
        }
        override suspend fun getPageUrl(page: SourcePage): String {
            calls += "pageUrl:${page.source.name}"
            return "$prefix:${page.url}"
        }
        override suspend fun getRelated(content: SourceContent): List<SourceContent> {
            calls += "related:${content.source.name}"
            return emptyList()
        }
    }

    private val mihon = Recording("m", listOf("MIHON_1", "SHARED"))
    private val parsers = Recording("p", listOf("PARSER_A", "SHARED"))
    private val runtime = RoutedSourceRuntime(listOf(
        RoutedSourceRuntime.Route({ it.startsWith("MIHON_") || it == "SHARED" }, mihon),
        RoutedSourceRuntime.Route({ it == "PARSER_A" || it == "SHARED" }, parsers),
    ))

    @Test
    fun `sources are merged once per name and each call reaches the owning runtime`() = runTest {
        assertEquals(listOf("MIHON_1", "SHARED", "PARSER_A"), runtime.getSources().map { it.name })
        runtime.describe("PARSER_A")
        runtime.getList("MIHON_1", 3, null, null)
        assertEquals(listOf("describe:PARSER_A"), parsers.calls)
        assertEquals(listOf("list:MIHON_1:3"), mihon.calls)
        // The first matching route wins when two runtimes claim the same name.
        runtime.describe("SHARED")
        assertEquals(listOf("describe:SHARED"), mihon.calls.drop(1))
    }

    @Test
    fun `content chapter and page calls route by their own source name`() = runTest {
        val source = SourceRef("PARSER_A", "en", "MANGA")
        val chapter = SourceChapter(1, null, 1f, 0, "/c", null, 0, null, source)
        val content = SourceContent(2, "t", emptySet(), "/u", "https://x/u", 0f, null, null, emptySet(), null, emptySet(), source)
        val page = SourcePage(3, "/p", null, source)
        runtime.getPages(chapter, null)
        runtime.getDetails(content, SourceDetailsFetchMode.ALLOW_CACHE)
        runtime.getRelated(content)
        assertEquals("p:/p", runtime.getPageUrl(page))
        assertEquals(listOf("pages:PARSER_A", "details:PARSER_A", "related:PARSER_A", "pageUrl:PARSER_A"), parsers.calls)
        assertTrue(mihon.calls.isEmpty())
    }

    @Test
    fun `an unowned source is unavailable and optional operations stay unsupported`() = runTest {
        assertThrows<SourceUnavailableException> { kotlinx.coroutines.runBlocking { runtime.describe("UNKNOWN") } }
        assertThrows<SourceOperationUnsupportedException> { kotlinx.coroutines.runBlocking { runtime.getPreferences("PARSER_A") } }
    }
}
