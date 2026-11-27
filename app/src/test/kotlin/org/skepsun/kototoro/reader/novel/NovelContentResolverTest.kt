package org.skepsun.kototoro.reader.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class NovelContentResolverTest {

    private val remoteSource = object : ContentSource {
        override val name: String = "TEST_NOVEL_SOURCE"
        override val locale: String = "zh"
        override val contentType: ContentType = ContentType.NOVEL
    }

    private fun testChapter(id: Long = 1L, source: ContentSource = remoteSource) = ContentChapter(
        id = id,
        title = "Chapter $id",
        number = id.toFloat(),
        volume = 0,
        url = "https://example.com/ch/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = source,
    )

    private fun testContent(
        id: Long = 100L,
        title: String = "Test Novel",
        url: String = "https://example.com/novel/$id",
        source: ContentSource = remoteSource,
        chapters: List<ContentChapter>? = null,
    ) = Content(
        id = id,
        title = title,
        altTitles = emptySet(),
        url = url,
        publicUrl = url,
        rating = -1f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
        chapters = chapters,
    )

    @Test
    fun `returns null when intent and fallback are both null`() = runTest {
        val result = resolveNovelReaderContent(
            resolveIntent = { null },
            fallbackManga = null,
            getRemoteContent = { error("should not be called") },
            findSavedContent = { error("should not be called") },
            getLocalDetails = { error("should not be called") },
            parseLocalFallback = { error("should not be called") },
        )

        assertNull(result)
    }

    @Test
    fun `falls back to parcelable manga when resolveIntent fails`() = runTest {
        val fallback = testContent(id = 42L, title = "Fallback Novel", chapters = listOf(testChapter()))
        var remoteLookupCalled = false

        val result = resolveNovelReaderContent(
            resolveIntent = { throw IllegalStateException("db unavailable") },
            fallbackManga = fallback,
            getRemoteContent = {
                remoteLookupCalled = true
                null
            },
            findSavedContent = { null },
            getLocalDetails = { error("should not be called") },
            parseLocalFallback = { error("should not be called") },
        )

        assertFalse(remoteLookupCalled)
        assertEquals(fallback, result?.manga)
        assertEquals(fallback, result?.originalContent)
        assertFalse(result!!.isLocal)
    }

    @Test
    fun `rethrows cancellation exception instead of swallowing it`() {
        assertThrows(CancellationException::class.java) {
            runTest {
                resolveNovelReaderContent(
                    resolveIntent = { throw CancellationException("activity destroyed") },
                    fallbackManga = testContent(),
                    getRemoteContent = { null },
                    findSavedContent = { null },
                    getLocalDetails = { it },
                    parseLocalFallback = { null },
                )
            }
        }
    }

    @Test
    fun `restores remote metadata for local seed and fixes local source`() = runTest {
        val localSeed = testContent(
            url = "file:///storage/emulated/0/novels/book.cbz",
            source = LocalNovelSource,
        )
        val remoteMeta = testContent(
            url = "https://example.com/novel/100",
            source = remoteSource,
        )
        val savedLocal = testContent(
            url = "file:///storage/emulated/0/novels/book.cbz",
            source = UnknownContentSource,
            chapters = listOf(testChapter(1L, LocalNovelSource)),
        )
        var remoteLookupCalled = false
        var localDetailsCalled = false

        val result = resolveNovelReaderContent(
            resolveIntent = { localSeed },
            fallbackManga = null,
            getRemoteContent = {
                remoteLookupCalled = true
                remoteMeta
            },
            findSavedContent = { savedLocal },
            getLocalDetails = {
                localDetailsCalled = true
                it
            },
            parseLocalFallback = { error("should not be called") },
        )

        assertTrue(remoteLookupCalled)
        assertFalse(localDetailsCalled)
        assertEquals(remoteMeta, result?.originalContent)
        assertEquals(remoteSource, result?.manga?.source)
        assertEquals(remoteMeta.url, result?.manga?.url)
        assertEquals(savedLocal.chapters, result?.manga?.chapters)
        assertTrue(result!!.isLocal)
    }

    @Test
    fun `refetches local details and falls back to parser when local chapters are empty`() = runTest {
        val seed = testContent(url = "file:///novels/empty.epub", source = LocalNovelSource)
        val savedEmpty = seed.copy(chapters = emptyList())
        val parsedChapters = listOf(testChapter(10L, LocalNovelSource))
        val parsedContent = seed.copy(chapters = parsedChapters)
        val calls = mutableListOf<String>()

        val result = resolveNovelReaderContent(
            resolveIntent = { seed },
            fallbackManga = null,
            getRemoteContent = {
                calls += "getRemoteContent"
                null
            },
            findSavedContent = {
                calls += "findSavedContent"
                savedEmpty
            },
            getLocalDetails = {
                calls += "getLocalDetails"
                it.copy(chapters = emptyList())
            },
            parseLocalFallback = { url ->
                calls += "parseLocalFallback:$url"
                parsedContent
            },
        )

        assertEquals(
            listOf(
                "getRemoteContent",
                "findSavedContent",
                "getLocalDetails",
                "parseLocalFallback:${seed.url}",
            ),
            calls,
        )
        assertEquals(parsedChapters, result?.manga?.chapters)
        assertTrue(result!!.isLocal)
    }

    @Test
    fun `skips parser fallback when getLocalDetails returns chapters`() = runTest {
        val seed = testContent(url = "cbz:///novels/book.cbz", source = LocalNovelSource)
        val savedEmpty = seed.copy(chapters = null)
        val refetched = seed.copy(chapters = listOf(testChapter(2L, LocalNovelSource)))
        var parserFallbackCalled = false

        val result = resolveNovelReaderContent(
            resolveIntent = { seed },
            fallbackManga = null,
            getRemoteContent = { null },
            findSavedContent = { savedEmpty },
            getLocalDetails = { refetched },
            parseLocalFallback = {
                parserFallbackCalled = true
                null
            },
        )

        assertFalse(parserFallbackCalled)
        assertEquals(refetched, result?.manga)
        assertTrue(result!!.isLocal)
    }
}
