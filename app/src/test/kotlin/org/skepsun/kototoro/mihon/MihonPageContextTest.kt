package org.skepsun.kototoro.mihon

import android.net.Uri
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.parsers.model.ContentChapter

@OptIn(ExperimentalCoroutinesApi::class)
class MihonPageContextTest {

    private val upstream = mockk<HttpSource> {
        every { id } returns 609L
        every { name } returns "Comix fixture"
        every { lang } returns "en"
        every { baseUrl } returns "https://example.org"
        every { supportsLatest } returns false
    }
    private val source = MihonMangaSource(upstream, "test.comix")
    private val cache = mockk<MemoryContentCache>(relaxed = true) {
        coEvery { getPages(any(), any()) } returns null
    }
    private val repository = MihonMangaRepository(source, cache)
    private val chapterUrl = "https://example.org/chapter/609?token=a+b&title=正文"
    private val chapter = ContentChapter(
        id = 609L,
        title = "Chapter",
        number = 1f,
        volume = 0,
        url = chapterUrl,
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = source,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        // Android's Uri is stubbed in JVM tests; retain its query decoding behavior.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val parameters = URI(firstArg<String>()).rawQuery.orEmpty().split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            mockk<Uri> {
                every { getQueryParameter(any()) } answers { parameters[firstArg<String>()] }
            }
        }
        coEvery { upstream.getImageUrl(any()) } answers {
            val page = firstArg<Page>()
            assertEquals(chapterUrl, page.url)
            imageUrl(page.index)
        }
        every { upstream.imageRequest(any()) } answers {
            val page = firstArg<Page>()
            assertEquals(chapterUrl, page.url)
            Request.Builder().url(imageUrl(page.index)).build()
        }
        every { upstream.getPageHeaders(any()) } returns okhttp3.Headers.Builder().build()
        coEvery { upstream.getImage(any()) } answers {
            val page = firstArg<Page>()
            assertEquals(imageUrl(page.index), page.imageUrl)
            val request = upstream.imageRequest(page)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("page-${page.index}".toResponseBody())
                .build()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `pages sharing a chapter URL resolve to distinct images`() = runTest {
        val indices = listOf(0, 1, 2)
        coEvery { upstream.getPageList(any()) } returns indices.map { Page(it, chapterUrl) }

        val pages = repository.getPages(chapter)
        assertEquals(indices.size, pages.map { it.id }.toSet().size)
        assertNotEquals(indices.map(Int::toLong), pages.map { it.id })
        val resolved = pages.map { repository.getPageUrl(it) }

        assertEquals(indices.map(::imageUrl), resolved)
        val images = pages.mapIndexed { index, page ->
            repository.fetchPageResponse(resolved[index], page)!!.use { it.body.string() }
        }
        assertEquals(indices.map { "page-$it" }, images)
    }

    @Test
    fun `resolved image requests preserve the extension page index`() = runTest {
        val indices = listOf(0, 3, 7)
        coEvery { upstream.getPageList(any()) } returns indices.map { Page(it, chapterUrl, imageUrl(it)) }

        val pages = repository.getPages(chapter)
        val requests = pages.map { repository.createPageRequest(repository.getPageUrl(it), it) }
        val images = pages.map { page ->
            repository.fetchPageResponse(repository.getPageUrl(page), page)!!.use { it.body.string() }
        }

        assertEquals(indices.map(::imageUrl), requests.map { it.url.toString() })
        assertEquals(indices.map { "page-$it" }, images)
    }

    @Test
    fun `URL resolution retains source indices instead of list positions`() = runTest {
        val indices = listOf(3, 7)
        coEvery { upstream.getPageList(any()) } returns indices.map { Page(it, chapterUrl) }

        val pages = repository.getPages(chapter)

        assertEquals(indices.map(::imageUrl), pages.map { repository.getPageUrl(it) })
    }

    private fun imageUrl(index: Int) = "https://images.example.org/$index.webp#scramble=$index"
}
