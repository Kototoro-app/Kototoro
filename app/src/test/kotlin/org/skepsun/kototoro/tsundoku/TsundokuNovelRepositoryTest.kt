package org.skepsun.kototoro.tsundoku

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import android.app.Application
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.extensions.recovery.SourceRefreshReporter
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // getChapterList(manga, RefreshContext) is deprecated but is the Tsundoku entry point.
class TsundokuNovelRepositoryTest {

    private val upstreamId = 9001L

    private fun newSource(upstream: Source, isNsfw: Boolean = false): TsundokuNovelSource = TsundokuNovelSource(
        upstreamSource = upstream,
        pkgName = "eu.kanade.tachiyomi.novelextension.en.test",
        isNsfw = isNsfw,
    )

    private fun mockUpstream(isNovel: Boolean = true): HttpSource {
        val upstream = mockk<HttpSource>()
        every { upstream.id } returns upstreamId
        every { upstream.name } returns "Test Novel"
        every { upstream.lang } returns "en"
        every { upstream.isNovelSource() } returns isNovel
        every { upstream.supportsLatest } returns false
        every { upstream.baseUrl } returns "https://example.org"
        return upstream
    }

    private fun smanga(url: String, title: String, thumbnail: String? = null): SManga = SManga.create().apply {
        this.url = url
        this.title = title
        this.thumbnail_url = thumbnail
    }

    private fun sChapter(url: String, name: String, number: Float): SChapter = SChapter.create().apply {
        this.url = url
        this.name = name
        this.chapter_number = number
    }

    private fun chapter(
        source: TsundokuNovelSource,
        id: Long,
        url: String,
        title: String,
        number: Float,
    ): ContentChapter = ContentChapter(
        id = id,
        title = title,
        number = number,
        volume = 0,
        url = url,
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = source,
    )

    private fun seedContent(source: TsundokuNovelSource, chapters: List<ContentChapter>): Content = Content(
        id = 42L,
        title = "Novel",
        altTitles = emptySet(),
        url = "/novel/1",
        publicUrl = "https://example.org/novel/1",
        rating = -1f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        chapters = chapters,
        source = source,
    )

    private fun repository(
        source: TsundokuNovelSource,
        reporter: SourceRefreshReporter,
        cache: MemoryContentCache = MemoryContentCache(mockk<Application>(relaxed = true)),
    ): TsundokuNovelRepository = TsundokuNovelRepository(
        source = source,
        cache = cache,
        refreshReporter = reporter,
    )

    // ==================== getList ====================

    @Test
    fun `getList maps ABI popular mangas to Content`() = runTest {
        val upstream = mockUpstream()
        coEvery { upstream.getPopularManga(1) } returns MangasPage(
            mangas = listOf(smanga("/novel/1", "Novel One", "https://example.org/cover.jpg")),
            hasNextPage = false,
        )
        val source = newSource(upstream)
        val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

        val list = repo.getList(offset = 0, order = SortOrder.POPULARITY, filter = null)

        assertEquals(1, list.size)
        assertEquals("Novel One", list[0].title)
        assertEquals("https://example.org/cover.jpg", list[0].coverUrl)
        assertEquals("TSUNDOKU_9001", list[0].source.name)
        assertEquals("/novel/1", list[0].url)
        coVerify(exactly = 1) { upstream.getPopularManga(1) }
    }

    @Test
    fun `getList routes to getLatestUpdates for UPDATED order when supported`() = runTest {
        val upstream = mockUpstream()
        every { upstream.supportsLatest } returns true
        coEvery { upstream.getLatestUpdates(1) } returns MangasPage(listOf(smanga("/novel/2", "Latest")), false)
        val repo = repository(newSource(upstream), mockk<SourceRefreshReporter>(relaxed = true))

        val list = repo.getList(offset = 0, order = SortOrder.UPDATED, filter = null)

        assertEquals(listOf("Latest"), list.map { it.title })
        coVerify { upstream.getLatestUpdates(1) }
    }

    @Test
    fun `getList returns empty list when upstream is not a CatalogueSource`() = runTest {
        val upstream = mockk<Source>()
        every { upstream.id } returns upstreamId
        every { upstream.name } returns "Test Novel"
        every { upstream.lang } returns "en"
        every { upstream.isNovelSource() } returns true
        every { upstream.supportsLatest } returns false
        val repo = repository(newSource(upstream), mockk<SourceRefreshReporter>(relaxed = true))

        val list = repo.getList(offset = 0, order = SortOrder.POPULARITY, filter = ContentListFilter(query = "x"))

        assertTrue(list.isEmpty())
    }

    // ==================== getDetails / RefreshContext ====================

    @Test
    fun `getDetails uses RefreshContext variant for content with existing chapters`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream()
            val source = newSource(upstream)
            val chapters = listOf(
                chapter(source, 11L, "/c1", "Ch 1", 1f),
                chapter(source, 12L, "/c2", "Ch 2", 2f),
            )
            val seed = seedContent(source, chapters)

            coEvery { upstream.getMangaDetails(any()) } returns
                smanga("/novel/1", "Novel Details", "https://example.org/cover.jpg")
            val contextSlot = slot<RefreshContext>()
            coEvery { upstream.getChapterList(any<SManga>(), capture(contextSlot)) } returns listOf(
                sChapter("/c2", "Ch 2", 2f),
                sChapter("/c1", "Ch 1", 1f),
            )
            val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

            val details = repo.getDetails(seed)

            assertTrue(contextSlot.isCaptured)
            assertEquals(42L, contextSlot.captured.mangaId)
            assertEquals(2, contextSlot.captured.existingChapters.size)
            assertEquals(listOf("/c1", "/c2"), contextSlot.captured.existingChapters.map { it.url })
            assertFalse(contextSlot.captured.forceRefresh)
            assertEquals("Novel Details", details.title)
            assertEquals(42L, details.id)
            assertEquals(listOf("Ch 1", "Ch 2"), details.chapters.orEmpty().map { it.title })
            coVerify { upstream.getMangaDetails(any()) }
            coVerify { upstream.getChapterList(any<SManga>(), any<RefreshContext>()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `getDetails uses getMangaUpdate for brand-new content`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream()
            val source = newSource(upstream)
            val seed = seedContent(source, chapters = emptyList())

            coEvery {
                upstream.getMangaUpdate(any(), any(), any(), any())
            } returns SMangaUpdate(
                manga = smanga("/novel/1", "Fresh Novel"),
                chapters = listOf(sChapter("/c1", "Ch 1", 1f)),
            )
            val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

            val details = repo.getDetails(seed)

            assertEquals("Fresh Novel", details.title)
            assertEquals(listOf("Ch 1"), details.chapters.orEmpty().map { it.title })
            coVerify(exactly = 1) { upstream.getMangaUpdate(any(), any(), true, true) }
            coVerify(exactly = 0) { upstream.getChapterList(any<SManga>(), any<RefreshContext>()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `refreshChapters forces a refresh with content chapters`() = runTest {
        val upstream = mockUpstream()
        val source = newSource(upstream)
        val chapters = listOf(
            chapter(source, 11L, "/c1", "Ch 1", 1f),
            chapter(source, 12L, "/c2", "Ch 2", 2f),
        )
        val content = seedContent(source, chapters)

        val contextSlot = slot<RefreshContext>()
        coEvery { upstream.getChapterList(any<SManga>(), capture(contextSlot)) } returns listOf(
            sChapter("/c2", "Ch 2", 2f),
            sChapter("/c1", "Ch 1", 1f),
        )
        val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

        val refreshed = repo.refreshChapters(content, forceRefresh = true)

        assertTrue(contextSlot.isCaptured)
        assertEquals(42L, contextSlot.captured.mangaId)
        assertTrue(contextSlot.captured.forceRefresh)
        assertEquals(2, contextSlot.captured.existingChapters.size)
        assertEquals(listOf("/c1", "/c2"), contextSlot.captured.existingChapters.map { it.url })
        assertEquals(listOf("Ch 1", "Ch 2"), refreshed.map { it.title })
        assertEquals(listOf(1f, 2f), refreshed.map { it.number })
    }

    // ==================== getPages / getChapterContent (novel) ====================

    @Test
    fun `getPages encodes inline page text as base64 data url`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream(isNovel = true)
            val source = newSource(upstream)
            val page = Page(0, url = "/c1/body", imageUrl = null).apply {
                text = "Hello <b>Tsundoku</b>"
            }
            coEvery { upstream.getPageList(any()) } returns listOf(page)
            val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

            val pages = repo.getPages(chapter(source, 11L, "/c1", "Ch 1", 1f))

            assertEquals(1, pages.size)
            assertTrue(pages[0].url.startsWith("data:text/html;base64,"))
            val decoded = String(
                Base64.getDecoder().decode(pages[0].url.substringAfter("base64,")),
                Charsets.UTF_8,
            )
            assertEquals("Hello <b>Tsundoku</b>", decoded)
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `getPages falls back to fetchPageText when the page has no inline text`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream(isNovel = true)
            val source = newSource(upstream)
            val page = Page(0, url = "/c1/body", imageUrl = null)
            coEvery { upstream.getPageList(any()) } returns listOf(page)
            coEvery { upstream.fetchPageText(any()) } returns "Fetched novel body"
            val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

            val pages = repo.getPages(chapter(source, 11L, "/c1", "Ch 1", 1f))

            assertEquals(1, pages.size)
            val decoded = String(
                Base64.getDecoder().decode(pages[0].url.substringAfter("base64,")),
                Charsets.UTF_8,
            )
            assertEquals("Fetched novel body", decoded)
            coVerify { upstream.fetchPageText(any()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `getPages maps embedded image entries to tsundoku image urls`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream(isNovel = true)
            val source = newSource(upstream)
            val imagePage = Page(0, url = "/c1/pic", imageUrl = "https://cdn.example.org/img1.jpg")
            coEvery { upstream.getPageList(any()) } returns listOf(imagePage)
            val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

            val pages = repo.getPages(chapter(source, 11L, "/c1", "Ch 1", 1f))

            assertEquals(1, pages.size)
            assertTrue(pages[0].url.startsWith("tsundoku://image?page_url="))
            assertTrue("image_url=https%3A%2F%2Fcdn.example.org%2Fimg1.jpg" in pages[0].url)
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `getPageUrl passes data and plain urls through unchanged`() = runTest {
        val upstream = mockUpstream(isNovel = true)
        val source = newSource(upstream)
        val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

        val dataUrl = "data:text/html;base64,SGVsbG8="
        assertEquals(dataUrl, repo.getPageUrl(ContentPage(id = 1L, url = dataUrl, preview = null, source = source)))
        assertEquals(
            "https://cdn.example.org/direct.jpg",
            repo.getPageUrl(
                ContentPage(id = 2L, url = "https://cdn.example.org/direct.jpg", preview = null, source = source),
            ),
        )
    }

    @Test
    fun `getChapterContent concatenates escaped page bodies into html`() = runTest {
        val upstream = mockUpstream(isNovel = true)
        val source = newSource(upstream)
        val pages = listOf(
            Page(0, url = "/c1/p1", imageUrl = null).apply { text = "Para one & <two>" },
            Page(1, url = "/c1/p2", imageUrl = null).apply {
                text = "Dropped <p>inline</p>"
            },
            Page(2, url = "/c1/p3", imageUrl = "https://cdn.example.org/inline.jpg"),
        )
        coEvery { upstream.getPageList(any()) } returns pages
        val repo = repository(source, mockk<SourceRefreshReporter>(relaxed = true))

        val content = repo.getChapterContent(chapter(source, 11L, "/c1", "Ch 1", 1f))

        assertNotNull(content)
        assertTrue("<p>Para one &amp; &lt;two&gt;</p>" in content!!.html)
        assertTrue("<p>Dropped &lt;p&gt;inline&lt;/p&gt;</p>" in content.html)
        assertEquals(
            listOf(NovelChapterContent.NovelImage("https://cdn.example.org/inline.jpg", emptyMap())),
            content.images,
        )
    }

    // ==================== Error paths & refresh bookkeeping ====================

    @Test
    fun `getDetails records failure and rethrows on IOException`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream()
            val source = newSource(upstream)
            val seed = seedContent(source, listOf(chapter(source, 11L, "/c1", "Ch 1", 1f)))
            coEvery { upstream.getMangaDetails(any()) } throws IOException("boom")
            coEvery { upstream.getChapterList(any<SManga>(), any<RefreshContext>()) } returns emptyList()
            val reporter = mockk<SourceRefreshReporter>(relaxed = true)
            val repo = repository(source, reporter)

            assertThrows(IOException::class.java) { runBlocking { repo.getDetails(seed) } }

            coVerify(exactly = 1) { reporter.recordAttempt("TSUNDOKU_9001", 42L, any()) }
            coVerify(exactly = 1) { reporter.recordFailure("TSUNDOKU_9001", 42L, any()) }
            coVerify(exactly = 0) { reporter.recordSuccess(any(), any(), any()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `cancellation during details fetch is rethrown without failure recording`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream()
            val source = newSource(upstream)
            val seed = seedContent(source, listOf(chapter(source, 11L, "/c1", "Ch 1", 1f)))
            coEvery { upstream.getMangaDetails(any()) } throws CancellationException("cancelled")
            coEvery { upstream.getChapterList(any<SManga>(), any<RefreshContext>()) } returns emptyList()
            val reporter = mockk<SourceRefreshReporter>(relaxed = true)
            val repo = repository(source, reporter)

            assertThrows(CancellationException::class.java) { runBlocking { repo.getDetails(seed) } }

            coVerify(exactly = 1) { reporter.recordAttempt("TSUNDOKU_9001", 42L, any()) }
            coVerify(exactly = 0) { reporter.recordFailure(any(), any(), any()) }
            coVerify(exactly = 0) { reporter.recordSuccess(any(), any(), any()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `getPages rethrows page fetch IOExceptions without refresh bookkeeping`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream(isNovel = true)
            val source = newSource(upstream)
            coEvery { upstream.getPageList(any()) } throws IOException("page boom")
            val reporter = mockk<SourceRefreshReporter>(relaxed = true)
            val repo = repository(source, reporter)

            assertThrows(IOException::class.java) {
                runBlocking { repo.getPages(chapter(source, 11L, "/c1", "Ch 1", 1f)) }
            }

            // Page fetches are not content refreshes; refresh bookkeeping stays untouched.
            coVerify(exactly = 0) { reporter.recordFailure(any(), any(), any()) }
            coVerify(exactly = 0) { reporter.recordAttempt(any(), any()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    @Test
    fun `getDetails records success via the refresh reporter`() = runTest {
        setUpAndroidExecutors(testScheduler)
        try {
            val upstream = mockUpstream()
            val source = newSource(upstream)
            val seed = seedContent(source, listOf(chapter(source, 11L, "/c1", "Ch 1", 1f)))
            coEvery { upstream.getMangaDetails(any()) } returns smanga("/novel/1", "Novel Details")
            coEvery { upstream.getChapterList(any<SManga>(), any<RefreshContext>()) } returns emptyList()
            val reporter = mockk<SourceRefreshReporter>(relaxed = true)
            val repo = repository(source, reporter)

            repo.getDetails(seed, ContentRepository.DetailsFetchMode.FORCE_REFRESH)

            coVerify(exactly = 1) { reporter.recordAttempt("TSUNDOKU_9001", 42L, any()) }
            coVerify(exactly = 1) { reporter.recordSuccess("TSUNDOKU_9001", 42L, any()) }
            coVerify(exactly = 0) { reporter.recordFailure(any(), any(), any()) }
        } finally {
            tearDownAndroidExecutors()
        }
    }

    // ==================== Request plumbing ====================

    @Test
    fun `getImageClient comes from the HttpSource`() {
        val upstream = mockUpstream()
        val client = okhttp3.OkHttpClient()
        every { upstream.client } returns client
        val repo = repository(newSource(upstream), mockk<SourceRefreshReporter>(relaxed = true))

        assertEquals(client, repo.getImageClient())
    }

    @Test
    fun `getList falls back to absolute public url when getMangaUrl throws`() = runTest {
        val upstream = mockUpstream()
        coEvery { upstream.getPopularManga(1) } returns MangasPage(listOf(smanga("/novel/1", "Novel One")), false)
        val repo = repository(newSource(upstream), mockk<SourceRefreshReporter>(relaxed = true))

        val list = repo.getList(offset = 0, order = SortOrder.POPULARITY, filter = null)

        // getMangaUrl is unstubbed on the mock (throws) → getPublicContentUrl returns "" →
        // publicUrl falls back to the resolved absolute URL.
        assertEquals("https://example.org/novel/1", list[0].publicUrl)
    }

    private fun setUpAndroidExecutors(scheduler: TestCoroutineScheduler) {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        ArchTaskExecutor.getInstance().setDelegate(IMMEDIATE_TASK_EXECUTOR)
    }

    private fun tearDownAndroidExecutors() {
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
    }

    private companion object {
        private val IMMEDIATE_TASK_EXECUTOR = object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        }
    }
}
