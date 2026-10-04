package org.skepsun.kototoro.core.source

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentRepositoryFactory
import org.skepsun.kototoro.core.parser.EmptyContentRepository
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.SortOrder

class AndroidSourceRuntimeTest {
    private val sources = mockk<ContentSourcesRepository>()
    private val factory = mockk<ContentRepository.Factory>()
    private val repository = mockk<ContentRepository>()
    private val creation = mockk<ContentRepositoryFactory.CreationResult>()
    private val runtime = AndroidSourceRuntime(sources, factory)
    private val client = SourceProtocolClient(SourceEndpoint(runtime)) { "android-fixture" }
    private val content = protocolTestContent()
    private val chapter = protocolTestChapter()
    private val page = protocolTestPage()
    private val sourceName = protocolTestSource.name

    init {
        every { factory.createWithDiagnostics(any()) } returns creation
        every { creation.failureReason } returns null
        every { creation.repository } returns repository
        every { repository.source } returns protocolTestSource
    }

    @Test
    fun `desktop preference operations remain explicit unsupported in Android adapter`() = runBlocking {
        val read = assertThrows(SourceRemoteException::class.java) { runBlocking { client.getPreferences(sourceName) } }
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, read.error.code)
        val write = assertThrows(SourceRemoteException::class.java) { runBlocking { client.updatePreference(sourceName,
            "desktop-revision", "node", org.skepsun.kototoro.core.source.SourcePreferenceValue.Text("value")) } }
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, write.error.code)
        coVerify(exactly = 0) { repository.getList(any(), any(), any()) }
    }

    @Test
    fun `catalog uses existing enabled source policy`() = runBlocking {
        coEvery { sources.getEnabledSources() } returns listOf(protocolTestSource)
        assertEquals(listOf(protocolTestSource.toSourceRef()), client.getSources())
        coVerify(exactly = 1) { sources.getEnabledSources() }
        verify(exactly = 0) { factory.createWithDiagnostics(any()) }
    }

    @Test
    fun `descriptor exports canonical source capabilities sort and page index mode`() = runBlocking {
        every { repository.sortOrders } returns linkedSetOf(SortOrder.POPULARITY, SortOrder.RELEVANCE)
        every { repository.defaultSortOrder } returns SortOrder.RELEVANCE
        every { repository.filterCapabilities } returns ContentListFilterCapabilities(
            true, true, true, false, true, false, true, true,
        )
        every { repository.listPagingMode } returns ContentRepository.ListPagingMode.PAGE_INDEX
        assertEquals(
            SourceDescriptor(
                protocolTestSource.toSourceRef(), linkedSetOf("POPULARITY", "RELEVANCE"), "RELEVANCE",
                SourceFilterCapabilities(true, true, true, false, true, false, true, true), SourcePagingMode.PAGE_INDEX,
            ), client.describe(sourceName),
        )
        verify { factory.createWithDiagnostics(match { it.name == sourceName }) }
    }

    @Test
    fun `search and list retain offset null filter and explicit empty filter`() = runBlocking {
        coEvery { repository.getList(any(), any(), any()) } returns listOf(content)
        assertEquals(listOf(content.toSourceContent()), client.getList(sourceName, 31, "RELEVANCE", null))
        client.getList(sourceName, 0, null, SourceFilter())
        client.getList(sourceName, 1, "RELEVANCE", SourceFilter(query = "中文 + &", author = "作者"))
        coVerify { repository.getList(31, SortOrder.RELEVANCE, null) }
        coVerify { repository.getList(0, null, ContentListFilter.EMPTY) }
        coVerify { repository.getList(1, SortOrder.RELEVANCE, ContentListFilter(query = "中文 + &", author = "作者")) }
    }

    @Test
    fun `details pages page URL and related use existing repository with full opaque context`() = runBlocking {
        coEvery { repository.getDetails(content, ContentRepository.DetailsFetchMode.FORCE_REFRESH) } returns content
        coEvery { repository.getPages(chapter, "/next") } returns listOf(page)
        coEvery { repository.getPageUrl(page) } returns "https://fixture.test/image#scramble-data"
        coEvery { repository.getRelated(content) } returns listOf(content)
        assertEquals(content.toSourceContent(), client.getDetails(
            content.toSourceContent(), SourceDetailsFetchMode.FORCE_REFRESH,
        ))
        assertEquals(listOf(page.toSourcePage()), client.getPages(chapter.toSourceChapter(), "/next"))
        assertEquals("https://fixture.test/image#scramble-data", client.getPageUrl(page.toSourcePage()))
        assertEquals(listOf(content.toSourceContent()), client.getRelated(content.toSourceContent()))
        coVerify { repository.getDetails(content, ContentRepository.DetailsFetchMode.FORCE_REFRESH) }
        coVerify { repository.getPages(chapter, "/next") }
        coVerify { repository.getPageUrl(page) }
        coVerify { repository.getRelated(content) }
    }

    @Test
    fun `filters use existing repository options`() = runBlocking {
        val options = ContentListFilterOptions(availableTags = content.tags)
        coEvery { repository.getFilterOptions() } returns options
        assertEquals(options.toSourceFilterOptions(), client.getFilterOptions(sourceName))
    }

    @Test
    fun `unavailable sources are errors instead of successful empty results`() {
        every { creation.failureReason } returns ContentRepositoryFactory.FailureReason.NO_SUPPORTED_PROVIDER
        val error = assertThrows(SourceRemoteException::class.java) {
            runBlocking { client.getList(sourceName, 0, null, null) }
        }
        assertEquals(SourceErrorCode.SOURCE_UNAVAILABLE, error.error.code)
        coVerify(exactly = 0) { repository.getList(any(), any(), any()) }
    }

    @Test
    fun `empty fallback repository is rejected even without a failure reason`() {
        every { creation.repository } returns EmptyContentRepository(protocolTestSource)
        assertThrows(SourceUnavailableException::class.java) { runBlocking { runtime.describe(sourceName) } }
    }

    @Test
    fun `invalid order and empty source fail before parser calls`() {
        val invalidOrder = assertThrows(SourceRemoteException::class.java) {
            runBlocking { client.getList(sourceName, 0, "UNKNOWN_ORDER", null) }
        }
        assertEquals(SourceErrorCode.INVALID_ARGUMENT, invalidOrder.error.code)
        val emptySource = assertThrows(SourceRemoteException::class.java) {
            runBlocking { client.describe("") }
        }
        assertEquals(SourceErrorCode.INVALID_ARGUMENT, emptySource.error.code)
        coVerify(exactly = 0) { repository.getList(any(), any(), any()) }
        verify(exactly = 1) { factory.createWithDiagnostics(any()) }
    }

    @Test
    fun `repository cancellation crosses adapter endpoint and client unchanged`() {
        val cancellation = CancellationException("fixture cancellation")
        coEvery { repository.getPages(any(), any()) } throws cancellation
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { client.getPages(chapter.toSourceChapter(), null) }
        }
        assertTrue(error === cancellation)
    }
}
