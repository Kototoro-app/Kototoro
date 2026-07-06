package org.skepsun.kototoro.mihon

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import rx.Observable

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class TachiyomiXSourceCompatibilityTest {

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

	private companion object {
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
