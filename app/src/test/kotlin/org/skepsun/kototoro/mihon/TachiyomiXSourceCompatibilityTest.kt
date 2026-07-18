package org.skepsun.kototoro.mihon

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.zstd.Zstd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.mihon.compat.KotoNetworkHelper
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import rx.Observable

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class TachiyomiXSourceCompatibilityTest {

	@Test
	fun `host provides OkHttp Zstandard support for Mihon extensions`() {
		assertEquals("zstd", Zstd.encoding)
	}

	@Test
	fun `default client starts with Mihon compatibility interceptors`() {
		val baseClient = OkHttpClient.Builder()
			.addInterceptor(BrotliInterceptor)
			.addNetworkInterceptor(BrotliInterceptor)
			.build()
		val network = KotoNetworkHelper(
			baseClient = baseClient,
			cookieJar = CookieJar.NO_COOKIES,
		)

		assertEquals(UncaughtExceptionInterceptor::class.java, network.client.interceptors[0].javaClass)
		assertEquals(UserAgentInterceptor::class.java, network.client.interceptors[1].javaClass)
		assertEquals(CloudflareInterceptor::class.java, network.client.interceptors[2].javaClass)
		assertFalse(network.client.interceptors.any { it === BrotliInterceptor })
		assertFalse(network.client.networkInterceptors.any { it === BrotliInterceptor })
	}

	@Test
	fun `getMangaUpdate bridges to legacy detail and chapter APIs`() = runTest {
		val source = LegacyCatalogueSource()
		val originalManga = manga("/original", "Original")
		val oldChapters = listOf(chapter("/old", "Old"))

		val update = source.getMangaUpdate(
			manga = originalManga,
			chapters = oldChapters,
			fetchDetails = true,
			fetchChapters = true,
		)

		assertEquals("Updated", update.manga.title)
		assertEquals(listOf("New"), update.chapters.map { it.name })
	}

	@Test
	fun `getMangaUpdate keeps supplied values when update flags are false`() = runTest {
		val source = LegacyCatalogueSource()
		val originalManga = manga("/original", "Original")
		val oldChapters = listOf(chapter("/old", "Old"))

		val update = source.getMangaUpdate(
			manga = originalManga,
			chapters = oldChapters,
			fetchDetails = false,
			fetchChapters = false,
		)

		assertSame(originalManga, update.manga)
		assertSame(oldChapters, update.chapters)
	}

	@Test
	fun `combined manga update bypasses unsupported legacy request helpers`() = runTest {
		val source = CombinedUpdateHttpSource()

		val update = source.getMangaUpdate(
			manga = manga("token", "Original"),
			chapters = emptyList(),
			fetchDetails = true,
			fetchChapters = true,
		)

		assertEquals("Combined Details", update.manga.title)
		assertEquals(listOf("Combined Chapter"), update.chapters.map { it.name })
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `repository preserves manga and chapter memo across list details and pages`() = runTest {
		Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
		ArchTaskExecutor.getInstance().setDelegate(IMMEDIATE_TASK_EXECUTOR)
		try {
			val catalogueSource = MemoDependentHttpSource()
			val source = MihonMangaSource(catalogueSource, "test.extension")
			val repository = MihonMangaRepository(source, mockk<MemoryContentCache>(relaxed = true))

			val listedManga = repository.getList(offset = 0, order = null, filter = null).single()
			val details = repository.getDetails(listedManga, ContentRepository.DetailsFetchMode.FORCE_REFRESH)
			val pages = repository.getPages(details.chapters.orEmpty().single())

			assertEquals("required-slug", catalogueSource.receivedSlug)
			assertEquals("required-manga-id", catalogueSource.receivedMangaId)
			assertEquals("Combined Details", details.title)
			assertEquals(listOf("Combined Chapter"), details.chapters?.map { it.title })
			assertEquals(listOf("https://example.org/page.webp"), pages.map { it.url })
		} finally {
			ArchTaskExecutor.getInstance().setDelegate(null)
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `HttpSource exposes baseUrl as default home URL`() {
		val source = object : HttpSource() {
			override val baseUrl: String = "https://example.org"
			override val lang: String = "en"
			override val name: String = "Example"
			override val supportsLatest: Boolean = false

			override fun popularMangaRequest(page: Int): Request = unused()
			override fun popularMangaParse(response: Response): MangasPage = unused()
			override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
			override fun searchMangaParse(response: Response): MangasPage = unused()
			override fun latestUpdatesRequest(page: Int): Request = unused()
			override fun latestUpdatesParse(response: Response): MangasPage = unused()
			override fun mangaDetailsParse(response: Response): SManga = unused()
			override fun chapterListParse(response: Response): List<SChapter> = unused()
			override fun pageListParse(response: Response): List<Page> = unused()
			override fun imageUrlParse(response: Response): String = unused()
		}

		assertEquals("https://example.org", source.getHomeUrl())
	}

	@Test
	fun `HttpSource suspend popular manga uses coroutine HTTP path`() = runTest {
		val server = MockWebServer()
		server.enqueue(MockResponse().setBody("Title A"))
		server.start()
		try {
			val source = CoroutineHttpSource(server.url("/").toString().removeSuffix("/"))

			val page = source.getPopularManga(1)

			assertEquals(listOf("Title A"), page.mangas.map { it.title })
			assertEquals("/popular/1", server.takeRequest().path)
		} finally {
			server.shutdown()
		}
	}

	@Test
	fun `HttpSource suspend chapter list falls back to custom legacy fetch when helper is unsupported`() = runTest {
		val source = LegacyFetchHttpSource()

		val chapters = source.getChapterList(manga("/manga", "Manga"))

		assertEquals(listOf("Legacy Chapter"), chapters.map { it.name })
	}

	@Test
	fun `HttpSource suspend page list uses custom legacy fetch when request helper is not overridden`() = runTest {
		val source = LegacyPageFetchHttpSource()

		val pages = source.getPageList(chapter("chapter/path", "Chapter"))

		assertEquals(listOf("https://images.example.org/1.jpg"), pages.map { it.imageUrl })
	}

	@Test
	fun `HttpSource suspend chapter list uses fetchChapterList when both request helper and fetch are overridden`() = runTest {
		val source = LegacyFetchWithRequestHttpSource()

		val chapters = source.getChapterList(manga("/manga", "Manga"))

		assertEquals(listOf("Legacy Custom Chapter"), chapters.map { it.name })
	}

	@Test
	fun `HttpSource legacy chapter fetch can delegate to super without recursion`() = runTest {
		val server = MockWebServer()
		server.enqueue(MockResponse().setBody("Chapter From Super"))
		server.start()
		try {
			val source = SuperDelegatingFetchChapterHttpSource(server.url("/").toString().removeSuffix("/"))

			val chapters = source.getChapterList(manga("/manga", "Manga"))

			assertEquals(listOf("Chapter From Super!"), chapters.map { it.name })
			assertEquals("/manga", server.takeRequest().path)
		} finally {
			server.shutdown()
		}
	}

	@Test
	fun `HttpSource legacy manga details fetch can delegate to super without recursion`() = runTest {
		val server = MockWebServer()
		server.enqueue(MockResponse().setBody("Details From Super"))
		server.start()
		try {
			val source = SuperDelegatingFetchDetailsHttpSource(server.url("/").toString().removeSuffix("/"))

			val details = source.getMangaDetails(manga("/manga", "Manga"))

			assertEquals("Details From Super!", details.title)
			assertEquals("/manga", server.takeRequest().path)
		} finally {
			server.shutdown()
		}
	}

	private class LegacyCatalogueSource : CatalogueSource {
		override val id: Long = 1L
		override val name: String = "Legacy"
		override val lang: String = "en"
		override val supportsLatest: Boolean = true

		override fun getFilterList(): FilterList = FilterList()

		override fun fetchPopularManga(page: Int): Observable<MangasPage> {
			return Observable.just(MangasPage(emptyList(), false))
		}

		override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
			return Observable.just(MangasPage(emptyList(), false))
		}

		override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
			return Observable.just(MangasPage(emptyList(), false))
		}

		override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
			return Observable.just(manga("/updated", "Updated"))
		}

		override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
			return Observable.just(listOf(chapter("/new", "New")))
		}
	}

	private class CombinedUpdateHttpSource : HttpSource() {
		override val baseUrl: String = "https://example.org"
		override val lang: String = "en"
		override val name: String = "Combined Update"
		override val supportsLatest: Boolean = false

		override suspend fun getMangaUpdate(
			manga: SManga,
			chapters: List<SChapter>,
			fetchDetails: Boolean,
			fetchChapters: Boolean,
		): SMangaUpdate {
			return SMangaUpdate(
				manga = TachiyomiXSourceCompatibilityTest.manga("token", "Combined Details"),
				chapters = listOf(chapter("chapter-token", "Combined Chapter")),
			)
		}

		override fun mangaDetailsRequest(manga: SManga): Request = throw UnsupportedOperationException()
		override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()
		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun chapterListParse(response: Response): List<SChapter> = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class MemoDependentHttpSource : HttpSource() {
		override val baseUrl: String = "https://example.org"
		override val lang: String = "en"
		override val name: String = "Memo Dependent"
		override val supportsLatest: Boolean = false
		var receivedSlug: String? = null
		var receivedMangaId: String? = null

		override suspend fun getPopularManga(page: Int): MangasPage {
			return MangasPage(
				mangas = listOf(
					manga("token", "Listed Manga").apply {
						memo = buildJsonObject { put("slug", "required-slug") }
					},
				),
				hasNextPage = false,
			)
		}

		override suspend fun getMangaUpdate(
			manga: SManga,
			chapters: List<SChapter>,
			fetchDetails: Boolean,
			fetchChapters: Boolean,
		): SMangaUpdate {
			receivedSlug = manga.memo["slug"]?.jsonPrimitive?.content
			checkNotNull(receivedSlug)
			return SMangaUpdate(
				manga = manga.copy().apply { title = "Combined Details" },
				chapters = listOf(
					chapter("chapter-token", "Combined Chapter").apply {
						memo = buildJsonObject { put("mangaId", "required-manga-id") }
					},
				),
			)
		}

		override suspend fun getPageList(chapter: SChapter): List<Page> {
			receivedMangaId = chapter.memo["mangaId"]?.jsonPrimitive?.content
			checkNotNull(receivedMangaId)
			return listOf(Page(index = 0, imageUrl = "https://example.org/page.webp"))
		}

		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun chapterListParse(response: Response): List<SChapter> = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class CoroutineHttpSource(
		override val baseUrl: String,
	) : HttpSource() {
		override val client: OkHttpClient = OkHttpClient()
		override val lang: String = "en"
		override val name: String = "Coroutine"
		override val supportsLatest: Boolean = false

		override fun fetchPopularManga(page: Int): Observable<MangasPage> {
			throw AssertionError("suspend API must not call legacy fetchPopularManga")
		}

		override fun popularMangaRequest(page: Int): Request {
			return Request.Builder().url("$baseUrl/popular/$page").build()
		}

		override fun popularMangaParse(response: Response): MangasPage {
			return MangasPage(
				mangas = listOf(manga("/title-a", response.body.string())),
				hasNextPage = false,
			)
		}

		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun chapterListParse(response: Response): List<SChapter> = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class LegacyFetchHttpSource : HttpSource() {
		override val baseUrl: String = "https://example.org"
		override val lang: String = "en"
		override val name: String = "Legacy Fetch"
		override val supportsLatest: Boolean = false

		override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
			return Observable.just(listOf(chapter("/legacy", "Legacy Chapter")))
		}

		override fun chapterListRequest(manga: SManga): Request {
			throw UnsupportedOperationException()
		}

		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun chapterListParse(response: Response): List<SChapter> = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class LegacyPageFetchHttpSource : HttpSource() {
		override val baseUrl: String = "https://example.org"
		override val lang: String = "en"
		override val name: String = "Legacy Page Fetch"
		override val supportsLatest: Boolean = false

		override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
			return Observable.just(listOf(Page(0, "", "https://images.example.org/1.jpg")))
		}

		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun chapterListParse(response: Response): List<SChapter> = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class LegacyFetchWithRequestHttpSource : HttpSource() {
		override val baseUrl: String = "https://example.org"
		override val lang: String = "en"
		override val name: String = "Legacy Fetch With Request"
		override val supportsLatest: Boolean = false

		override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
			return Observable.just(listOf(chapter("/legacy-custom", "Legacy Custom Chapter")))
		}

		override fun chapterListRequest(manga: SManga): Request {
			return Request.Builder().url("$baseUrl/manga/path").build()
		}

		override fun chapterListParse(response: Response): List<SChapter> {
			return listOf(chapter("/parsed", "Parsed Chapter"))
		}

		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class SuperDelegatingFetchChapterHttpSource(
		override val baseUrl: String,
	) : HttpSource() {
		override val client: OkHttpClient = OkHttpClient()
		override val lang: String = "en"
		override val name: String = "Super Delegating Fetch"
		override val supportsLatest: Boolean = false

		override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
			return super.fetchChapterList(manga).map { chapters ->
				chapters.map { chapter ->
					chapter.apply { name += "!" }
				}
			}
		}

		override fun chapterListRequest(manga: SManga): Request {
			return Request.Builder().url(baseUrl + manga.url).build()
		}

		override fun chapterListParse(response: Response): List<SChapter> {
			return listOf(chapter("/chapter-from-super", response.body.string()))
		}

		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun mangaDetailsParse(response: Response): SManga = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private class SuperDelegatingFetchDetailsHttpSource(
		override val baseUrl: String,
	) : HttpSource() {
		override val client: OkHttpClient = OkHttpClient()
		override val lang: String = "en"
		override val name: String = "Super Delegating Details Fetch"
		override val supportsLatest: Boolean = false

		override fun headersBuilder(): Headers.Builder = Headers.Builder()

		override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
			return super.fetchMangaDetails(manga).map { details ->
				details.apply { title += "!" }
			}
		}

		override fun mangaDetailsParse(response: Response): SManga {
			return manga("/manga", response.body.string())
		}

		override fun popularMangaRequest(page: Int): Request = unused()
		override fun popularMangaParse(response: Response): MangasPage = unused()
		override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
		override fun searchMangaParse(response: Response): MangasPage = unused()
		override fun latestUpdatesRequest(page: Int): Request = unused()
		override fun latestUpdatesParse(response: Response): MangasPage = unused()
		override fun chapterListParse(response: Response): List<SChapter> = unused()
		override fun pageListParse(response: Response): List<Page> = unused()
		override fun imageUrlParse(response: Response): String = unused()
	}

	private companion object {
		private val IMMEDIATE_TASK_EXECUTOR = object : TaskExecutor() {
			override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
			override fun postToMainThread(runnable: Runnable) = runnable.run()
			override fun isMainThread(): Boolean = true
		}

		fun manga(url: String, title: String): SManga {
			return SManga.create().apply {
				this.url = url
				this.title = title
				artist = null
				author = null
				description = null
				genre = null
				status = SManga.UNKNOWN
				thumbnail_url = null
				update_strategy = UpdateStrategy.ALWAYS_UPDATE
				initialized = true
			}
		}

		fun chapter(url: String, name: String): SChapter {
			return SChapter.create().apply {
				this.url = url
				this.name = name
				date_upload = 0L
				chapter_number = -1f
				scanlator = null
			}
		}

		fun unused(): Nothing = throw UnsupportedOperationException("Unused in this test")
	}
}
