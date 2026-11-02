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
        val store = FileSourceImageStore(root.resolve("images"))
        runtime = ParserSourceRuntime(registry, platform, store, ebookAssets = store.directory)
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
    fun `browser session user agent matches requests when the platform supplies its own default`() = runBlocking {
        val source = "FIXTURE_KOTOTORO_ONLY"
        val sdkClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (request.header("User-Agent") == null) {
                request.newBuilder().header("User-Agent", "SDK desktop default").build()
            } else request)
        }.build()
        TestPlatform(root.resolve("sdk"), sdkClient).use { sdkPlatform ->
            ParserPluginRegistry(sdkPlatform).use { sdkRegistry ->
                sdkRegistry.load(Fixtures.kototoro, "kototoro-parsers")
                val sdkRuntime = ParserSourceRuntime(sdkRegistry, sdkPlatform)
                val session = sdkRuntime.browserSession(source)
                sdkRegistry.withParser(source) { handle ->
                    val client = ParserLoaderContext(sdkPlatform) { handle.parser }.httpClient
                    val request = okhttp3.Request.Builder().url(server.url("/list"))
                        .tag(org.skepsun.kototoro.parsers.model.ContentSource::class.java, handle.parser.source).build()
                    client.newCall(request).execute().close()
                }
                assertEquals(session.userAgent, requests.poll(5, TimeUnit.SECONDS)!!.getHeader("User-Agent"))
            }
        }
    }

    @Test
    fun `parser requests carry the parser headers and go through its interceptor`() = runBlocking {
        useServerAsDomain("FIXTURE_KOTOTORO_ONLY")
        registry.withParser("FIXTURE_KOTOTORO_ONLY") { handle ->
            val client = ParserLoaderContext(platform) { handle.parser }.httpClient
            val tagged = okhttp3.Request.Builder().url(server.url("/api/list"))
                .tag(org.skepsun.kototoro.parsers.model.ContentSource::class.java, handle.parser.source).build()
            client.newCall(tagged).execute().close()
            client.newCall(okhttp3.Request.Builder().url(server.url("/other")).build()).execute().close()
        }
        val parserRequest = requests.poll(5, TimeUnit.SECONDS)!!
        assertEquals("/api/list", parserRequest.path)
        assertEquals("kototoro", parserRequest.getHeader("X-Fixture-Arch"))
        assertEquals("https://${server.hostName}:${server.port}/", parserRequest.getHeader("Referer"))
        assertNotNull(parserRequest.getHeader("User-Agent"))
        val untagged = requests.poll(5, TimeUnit.SECONDS)!!
        assertEquals("/other", untagged.path)
        assertNull(untagged.getHeader("X-Fixture-Arch"), "a request no parser tagged is left alone")
    }

    @Test
    fun `EPUB only chapters download through parser headers and expose text and local illustrations`() = runBlocking {
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
                entry("OPS/book.opf", """<package><metadata><title>EPUB fixture</title></metadata><manifest>
                    <item id="a" href="first.xhtml" media-type="application/xhtml+xml"/>
                    <item id="b" href="second.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
                entry("OPS/first.xhtml", "<html><body><p>第一章正文</p><img src=\"pic.png\"/></body></html>")
                entry("OPS/second.xhtml", "<html><body><p>第二章正文</p></body></html>")
                zip.putNextEntry(java.util.zip.ZipEntry("OPS/pic.png"))
                zip.write(Fixtures.png)
                zip.closeEntry()
            }
        }.toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "application/epub+zip")
            }
        }
        useServerAsDomain("FIXTURE_KOTOTORO_ONLY")
        val item = runtime.getList("FIXTURE_KOTOTORO_ONLY", 0, null, null).first()
        val chapter = runtime.getDetails(item, SourceDetailsFetchMode.ALLOW_CACHE).chapters!!.first()
            .copy(ebookFormats = listOf("EPUB"))
        val text = runtime.getChapterContent(chapter, null)
        assertNotNull(text, "EPUB pages must not become a nil chapter body / NSURLErrorDomain -1014")
        requireNotNull(text)
        assertTrue(text.html.contains("第一章正文"))
        assertTrue(text.html.contains("第二章正文"))
        // Each chapter of the book is its own section, so a download can list them.
        assertEquals(2, text.sections.size)
        assertEquals(0, text.sections[0].start)
        val first = text.html.substring(0, text.sections[1].start)
        val second = text.html.substring(text.sections[1].start)
        assertTrue("第一章正文" in first && "第二章正文" !in first)
        assertTrue("第二章正文" in second && "第一章正文" !in second)
        val image = Path.of(java.net.URI(text.images.single().url))
        assertTrue(Files.readAllBytes(image).contentEquals(Fixtures.png))
        assertTrue(image.toRealPath().startsWith(root.resolve("images").toRealPath()))
        val request = requests.poll(5, TimeUnit.SECONDS)!!
        assertEquals("/book.epub", request.path)
        assertEquals("kototoro", request.getHeader("X-Fixture-Arch"))
        assertEquals("https://${server.hostName}:${server.port}/", request.getHeader("Referer"))
        assertTrue(request.getHeader("Accept")!!.contains("application/epub+zip"))
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
    fun `decrypted temporary file covers and pages are materialized without HTTP`() = runBlocking {
        val item = runtime.getList("FIXTURE_KOTOTORO_ONLY", 0, null, null).first()
        val file = Files.write(root.resolve("cg51_封面 image.png"), Fixtures.png)
        val local = item.copy(coverUrl = file.toUri().toString(), largeCoverUrl = file.toUri().toString())
        val cover = runtime.fetchCover(local, large = false)
        assertEquals(item.id, cover.contentId)
        assertTrue(Files.readAllBytes(root.resolve("images").resolve(cover.relativePath)).contentEquals(Fixtures.png))
        assertEquals(cover.sha256, runtime.fetchCover(local, large = true).sha256)
        val chapter = runtime.getDetails(item, SourceDetailsFetchMode.ALLOW_CACHE).chapters!!.first()
        val page = runtime.getPages(chapter, null).first().copy(url = file.toUri().toString())
        assertEquals(cover.sha256, runtime.fetchImage(page).sha256)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `inline image covers and pages use the same validated image store`() = runBlocking {
        val item = runtime.getList("FIXTURE_KOTOTORO_ONLY", 0, null, null).first()
        val url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(Fixtures.png)
        val cover = runtime.fetchCover(item.copy(coverUrl = url), large = false)
        assertEquals(Fixtures.png.size.toLong(), cover.byteSize)
        assertTrue(Files.readAllBytes(root.resolve("images").resolve(cover.relativePath)).contentEquals(Fixtures.png))
        val chapter = runtime.getDetails(item, SourceDetailsFetchMode.ALLOW_CACHE).chapters!!.first()
        val page = runtime.getPages(chapter, null).first().copy(url = url)
        assertEquals(cover.sha256, runtime.fetchImage(page).sha256)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `local images reject missing files invalid bytes and remote file authorities`() = runBlocking {
        val item = runtime.getList("FIXTURE_KOTOTORO_ONLY", 0, null, null).first()
        val invalid = Files.writeString(root.resolve("not-image"), "not an image").toUri().toString()
        for (url in listOf(root.resolve("missing.png").toUri().toString(), "file://remote/cover.png",
            "data:text/plain;base64,aGVsbG8=", "data:image/png;base64")) {
            assertThrows<SourceInvalidArgumentException> {
                runBlocking { runtime.fetchCover(item.copy(coverUrl = url), false) }
            }
        }
        assertThrows<java.io.IOException> { runBlocking { runtime.fetchCover(item.copy(coverUrl = invalid), false) } }
        assertThrows<java.io.IOException> {
            runBlocking { runtime.fetchCover(item.copy(coverUrl = "data:image/png;base64,"), false) }
        }
        assertTrue(requests.isEmpty())
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
