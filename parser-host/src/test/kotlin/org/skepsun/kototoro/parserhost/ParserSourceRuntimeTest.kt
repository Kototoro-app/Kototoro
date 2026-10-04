package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceEndpoint
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.core.source.SourceUnavailableException
import org.skepsun.kototoro.source.host.FileSourceImageStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ParserSourceRuntimeTest {
    @TempDir lateinit var root: Path
    private lateinit var platform: TestPlatform
    private lateinit var registry: ParserPluginRegistry
    private lateinit var runtime: ParserSourceRuntime
    private lateinit var server: MockWebServer
    private val requests = LinkedBlockingQueue<RecordedRequest>()

    /** (source, id base, architecture tag the fixture interceptor sends) for one source per plugin architecture. */
    private val cases = listOf(
        Triple("FIXTURE_KOTOTORO_ONLY", 1_000L, "kototoro"),
        Triple("FIXTURE_KOTATSU_ONLY", 2_000L, "kotatsu"),
        Triple("FIXTURE_TSUKI_ONLY", 3_000L, "tsuki"),
    )

    @BeforeEach
    fun open() {
        platform = TestPlatform(root)
        registry = ParserPluginRegistry(platform)
        registry.load(Fixtures.kototoro, "kototoro-parsers")
        registry.load(Fixtures.kotatsu, "kotatsu-parsers-redo")
        registry.load(Fixtures.tsuki, "uma")
        runtime = ParserSourceRuntime(registry, platform, FileSourceImageStore(root.resolve("images")))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return MockResponse().setBody(Buffer().write(Fixtures.png)).setHeader("Content-Type", "image/png")
                }
            }
            start()
        }
    }

    @AfterEach
    fun close() {
        server.shutdown()
        registry.close()
        platform.close()
    }

    private fun useServerAsDomain(source: String) {
        platform.store.open(ParserLoaderContext.namespace(source)).edit(
            SourcePreferenceEdit(changes = mapOf("domain" to SourcePreferenceValue.Text("${server.hostName}:${server.port}"))),
        )
    }

    @Test
    fun `every architecture lists searches pages and details through the portable contract`() = runBlocking {
        for ((source, base, _) in cases) {
            val descriptor = runtime.describe(source)
            assertEquals(setOf("POPULARITY", "UPDATED", "RATING"), descriptor.sortOrders, source)
            // Same rule as AbstractContentParser.defaultSortOrder: the first supported entry of the SortOrder enum.
            assertEquals("UPDATED", descriptor.defaultSortOrder, source)
            assertEquals(SourcePagingMode.OFFSET, descriptor.pagingMode)
            assertTrue(descriptor.filterCapabilities.isSearchSupported && descriptor.filterCapabilities.isMultipleTagsSupported)
            assertTrue(descriptor.isImageFetchingSupported && descriptor.isCoverFetchingSupported && descriptor.isPreferencesSupported)

            val first = runtime.getList(source, 0, null, null)
            assertEquals(listOf(base, base + 1), first.map { it.id }, source)
            assertEquals("$source UPDATED q= t=0 #0", first[0].title)
            assertEquals(source, first[0].source.name)
            assertEquals("$source POPULARITY q= t=0 #0", runtime.getList(source, 0, "POPULARITY", null)[0].title)
            val searched = runtime.getList(source, 2, "UPDATED", SourceFilter(query = "naruto"))
            assertEquals("$source UPDATED q=naruto t=0 #2", searched[0].title)
            assertEquals(listOf(base + 2, base + 3), searched.map { it.id })
            assertTrue(runtime.getList(source, 4, null, null).isEmpty())

            val details = runtime.getDetails(first[0], SourceDetailsFetchMode.FORCE_REFRESH)
            assertEquals(first[0].id, details.id)
            assertEquals("Fixture details for ${first[0].title}", details.description)
            val chapters = requireNotNull(details.chapters)
            assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
            assertEquals(source, chapters[0].source.name)

            val pages = runtime.getPages(chapters[0], null)
            assertEquals(3, pages.size)
            assertEquals(pages[0].url, runtime.getPageUrl(pages[0]))
            assertTrue(pages[0].url.startsWith("http://fixture.example/img/"))
            assertEquals(listOf("Action", "Drama"), runtime.getFilterOptions(source).availableTags.map { it.title }.sorted())
        }
    }

    @Test
    fun `tag filters reach the parser and unsupported controls are refused`() = runBlocking {
        val tags = runtime.getFilterOptions("FIXTURE_TSUKI_ONLY").availableTags
        val filtered = runtime.getList("FIXTURE_TSUKI_ONLY", 0, null, SourceFilter(tags = tags))
        assertEquals("FIXTURE_TSUKI_ONLY UPDATED q= t=2 #0", filtered[0].title)
        assertThrows<org.skepsun.kototoro.core.source.SourceOperationUnsupportedException> {
            runBlocking {
                runtime.getList("FIXTURE_TSUKI_ONLY", 0, null,
                    SourceFilter(dynamicFilters = listOf(org.skepsun.kototoro.core.source.SourceFilterChange("x", null))))
            }
        }
    }

    @Test
    fun `invalid requests fail with protocol exceptions`() {
        runBlocking {
            assertThrows<SourceInvalidArgumentException> { runBlocking { runtime.getList("FIXTURE_KOTATSU_ONLY", -1, null, null) } }
            assertThrows<SourceInvalidArgumentException> { runBlocking { runtime.getList("FIXTURE_KOTATSU_ONLY", 0, "NOPE", null) } }
            assertThrows<SourceInvalidArgumentException> { runBlocking { runtime.getList("FIXTURE_KOTATSU_ONLY", 0, "ALPHABETICAL", null) } }
            assertThrows<SourceInvalidArgumentException> { runBlocking { runtime.describe("") } }
            assertThrows<SourceUnavailableException> { runBlocking { runtime.describe("NO_SUCH_SOURCE") } }
        }
    }

    @Test
    fun `images and covers go through the parser interceptor with the shared client`() = runBlocking {
        for ((source, _, tag) in cases) {
            useServerAsDomain(source)
            val item = runtime.getList(source, 0, null, null)[0]
            assertTrue(item.coverUrl!!.startsWith("http://${server.hostName}:${server.port}/cover/"), item.coverUrl)
            val page = runtime.getPages(requireNotNull(runtime.getDetails(item, SourceDetailsFetchMode.ALLOW_CACHE).chapters)[0], null)[0]

            requests.clear()
            val artifact = runtime.fetchImage(page)
            assertEquals(page.id, artifact.pageId)
            assertEquals("image/png", artifact.contentType)
            assertEquals(Fixtures.png.size.toLong(), artifact.byteSize)
            val request = requireNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals(tag, request.getHeader("X-Fixture-Arch"), source)
            assertEquals("https://${server.hostName}:${server.port}/", request.getHeader("Referer"))
            assertEquals(platform.defaultUserAgent, request.getHeader("User-Agent"))
            assertTrue(request.getHeader("Accept")!!.startsWith("image/avif"))
            assertEquals("/img/${page.url.substringAfter("/img/")}", request.path)

            val cover = runtime.fetchCover(item, large = false)
            assertEquals(item.id, cover.contentId)
            assertEquals(artifact.sha256, cover.sha256)
            val coverRequest = requireNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals(tag, coverRequest.getHeader("X-Fixture-Arch"))
            assertTrue(coverRequest.path!!.startsWith("/cover/"))
        }
    }

    @Test
    fun `an http failure is reported and nothing is stored`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        useServerAsDomain("FIXTURE_KOTATSU_ONLY")
        val page = runtime.getPages(
            requireNotNull(runtime.getDetails(runtime.getList("FIXTURE_KOTATSU_ONLY", 0, null, null)[0], SourceDetailsFetchMode.ALLOW_CACHE).chapters)[0],
            null,
        )[0]
        assertThrows<java.io.IOException> { runBlocking { runtime.fetchImage(page) } }
        assertEquals(0, Files.list(root.resolve("images")).use { files -> files.filter { it.toString().endsWith(".img") }.count() })
    }

    @Test
    fun `settings come from the parser's declared keys and persist`() = runBlocking {
        val screen = runtime.getPreferences("FIXTURE_KOTATSU_ONLY")
        val domain = screen.nodes.single { it.key == "domain" }
        assertEquals(SourcePreferenceKind.CHOICE, domain.kind)
        assertEquals(listOf("fixture.example", "mirror.example"), domain.choices.map { it.value })
        assertEquals(SourcePreferenceValue.Text("fixture.example"), domain.value)
        assertNotNull(screen.nodes.single { it.key == "user_agent" })

        val update = runtime.updatePreference(screen.source.name, screen.revision, domain.id, SourcePreferenceValue.Text("mirror.example"))
        assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, update.status)
        assertEquals(SourcePreferenceValue.Text("mirror.example"), update.screen.nodes.single { it.key == "domain" }.value)
        assertTrue(runtime.getList("FIXTURE_KOTATSU_ONLY", 0, null, null)[0].publicUrl.startsWith("https://mirror.example/"))

        // The old revision is stale, and a value that is no host name is rejected without being stored.
        assertThrows<SourceInvalidArgumentException> {
            runBlocking { runtime.updatePreference(screen.source.name, screen.revision, domain.id, SourcePreferenceValue.Text("fixture.example")) }
        }
        val bad = runtime.updatePreference(screen.source.name, update.screen.revision, domain.id, SourcePreferenceValue.Text("not a host/"))
        assertEquals(SourcePreferenceUpdateStatus.REJECTED, bad.status)
        assertEquals(SourcePreferenceValue.Text("mirror.example"), bad.screen.nodes.single { it.key == "domain" }.value)
        // Presets are only suggestions: a private mirror is accepted as well.
        val custom = runtime.updatePreference(screen.source.name, bad.screen.revision, domain.id, SourcePreferenceValue.Text(" own.mirror.example "))
        assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, custom.status)
        assertEquals(SourcePreferenceValue.Text("own.mirror.example"), custom.screen.nodes.single { it.key == "domain" }.value)
    }

    @Test
    fun `novel sources return chapter bodies and text only pages are assembled from data URLs`() = runBlocking {
        assertTrue(runtime.describe("FIXTURE_KOTOTORO_ONLY").isChapterContentSupported)
        val novel = runtime.getList("FIXTURE_KOTOTORO_ONLY", 0, null, null)[0]
        val chapters = requireNotNull(runtime.getDetails(novel, SourceDetailsFetchMode.ALLOW_CACHE).chapters)
        val body = requireNotNull(runtime.getChapterContent(chapters[0], null))
        assertTrue(body.html.startsWith("<p>Body of Chapter 1</p><img src=\"http://"), body.html)
        assertEquals(mapOf("X-Test" to "1"), body.images.single().headers)
        assertTrue(body.images.single().url.contains("/illustration/${chapters[0].id}.png"))

        // A parser without a body call: its text pages (data: URLs) are joined; image pages give no text at all.
        assertFalse(runtime.describe("FIXTURE_KOTATSU_ONLY").isChapterContentSupported)
        val manga = runtime.getList("FIXTURE_KOTATSU_ONLY", 0, null, null)[0]
        val mangaChapters = requireNotNull(runtime.getDetails(manga, SourceDetailsFetchMode.ALLOW_CACHE).chapters)
        assertEquals("<p>First</p>\n<p>Second</p>", runtime.getChapterContent(mangaChapters[1], null)?.html)
        assertNull(runtime.getChapterContent(mangaChapters[0], null))
    }

    @Test
    fun `the JSON endpoint serves the same runtime`() = runBlocking {
        val counter = AtomicLong()
        val client: SourceRuntime = SourceProtocolClient(SourceEndpoint(runtime)) { "parser-${counter.incrementAndGet()}" }
        assertEquals(4, client.getSources().size)
        val items = client.getList("FIXTURE_TSUKI_ONLY", 0, "UPDATED", SourceFilter(query = "x"))
        assertEquals(listOf(3_000L, 3_001L), items.map { it.id })
        val details = client.getDetails(items[0], SourceDetailsFetchMode.FORCE_REFRESH)
        assertEquals(2, details.chapters!!.size)
        assertEquals(3, client.getPages(details.chapters!![0], null).size)
        // Chapter content crosses the wire too, including its absence.
        val novel = client.getList("FIXTURE_KOTOTORO_ONLY", 0, null, null)[0]
        val chapter = client.getDetails(novel, SourceDetailsFetchMode.ALLOW_CACHE).chapters!![0]
        assertTrue(client.getChapterContent(chapter, null)!!.html.startsWith("<p>Body of Chapter 1</p>"))
        assertNull(client.getChapterContent(details.chapters!![0], null))
    }
}
