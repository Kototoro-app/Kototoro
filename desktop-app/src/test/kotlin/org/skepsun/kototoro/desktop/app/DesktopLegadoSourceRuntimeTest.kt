package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.javascript.JavaScriptContext
import org.skepsun.kototoro.core.javascript.RhinoJavaScriptEngine
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.core.model.jsonsource.BookInfoRule
import org.skepsun.kototoro.core.model.jsonsource.ContentRule
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.model.jsonsource.SearchRule
import org.skepsun.kototoro.core.model.jsonsource.TocRule
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import org.skepsun.kototoro.core.parser.legado.AnalyzeRule
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class DesktopLegadoSourceRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `search details and paginated novel content use shared Legado rules`() {
        val log = directory.resolve("legado-runtime.log")
        val process = ProcessBuilder(
            DesktopProbes.javaExecutable,
            "-cp", System.getProperty("kototoro.desktop.test.classpath"),
            DesktopLegadoRuntimeProbe::class.java.name,
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Desktop Legado probe timed out")
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `catalog keeps novel sources and counts the entries it cannot run`() {
        val catalog = """[
            {"bookSourceName":"Novel","bookSourceUrl":"https://novel.test","bookSourceType":0,"searchUrl":"/s?q={{key}}"},
            {"bookSourceName":"Audio","bookSourceUrl":"https://audio.test","bookSourceType":1},
            {"bookSourceName":"Local","bookSourceUrl":"local"}
        ]"""
        val imported = requireNotNull(DesktopLegadoCatalog.read(catalog))
        assertEquals(2, imported.skipped)
        val stored = imported.sources.single()
        assertTrue(DesktopLegadoSourceRuntime.owns(stored.id), stored.id)
        assertEquals("/s?q={{key}}", DesktopLegadoCatalog.source(stored)?.config?.searchUrl)

        val single = DesktopLegadoCatalog.read("""{"bookSourceName":"One","bookSourceUrl":"https://one.test"}""")
        assertEquals(listOf("One"), single?.sources?.map { it.name })
        // A TVBox document is left to the TVBox importer.
        assertEquals(null, DesktopLegadoCatalog.read("""{"sites":[{"key":"a","api":"https://a.test/api.php/provide/vod"}]}"""))
    }

    /** Opt-in: `-PrealLegadoCatalog=<file>` reports how each source of a real catalog fares; it asserts nothing per site. */
    @Test
    fun `a real catalog runs through the desktop runtime`() {
        val catalog = System.getProperty("kototoro.real.legado")
        org.junit.jupiter.api.Assumptions.assumeTrue(!catalog.isNullOrBlank())
        val log = directory.resolve("legado-real.log")
        val process = ProcessBuilder(
            DesktopProbes.javaExecutable,
            "-cp", System.getProperty("kototoro.desktop.test.classpath"),
            DesktopRealLegadoProbe::class.java.name, catalog, System.getProperty("kototoro.real.legado.query") ?: "斗罗",
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(40, TimeUnit.MINUTES), "Real Legado probe timed out")
            // Script errors the engine swallows are listed too, including classes the sandbox refused: each of those is
            // either a gap in the allow-list or a script to distrust.
            val report = Files.readAllLines(log)
                .filter { it.startsWith("LEGADO ") || "Java 类" in it || "Java class" in it || it.startsWith("严重") || it.startsWith("SEVERE") || it.startsWith("警告") }.map { it.take(300) }.distinct().joinToString("\n")
            println(report)
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal object DesktopLegadoRuntimeProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val source = DesktopLegadoSource(
            "JSON_LEGADO_TEST",
            LegadoBookSource(
                bookSourceName = "Example",
                bookSourceUrl = "https://example.test",
                searchUrl = "/search?q={{key}}",
                exploreUrl = "Latest::/latest?p={{page}}\nRanking::/rank\nHeading",
                ruleSearch = SearchRule(bookList = "class.book", name = "tag.a@text", bookUrl = "tag.a@href"),
                ruleBookInfo = BookInfoRule(intro = "class.intro@text", tocUrl = "class.toc@href"),
                ruleToc = TocRule(
                    chapterList = "class.chapter", chapterName = "tag.a@text", chapterUrl = "tag.a@href",
                    nextTocUrl = "class.next@href",
                ),
                ruleContent = ContentRule(content = "class.content@html", nextContentUrl = "class.next@href"),
            ),
        )
        val documents = mapOf(
            "https://example.test/search?q=novel" to """<div class="book"><a href="/book/1">First</a></div>""",
            "https://example.test/latest?p=1" to """<div class="book"><a href="/book/2">Newest</a></div>""",
            "https://example.test/rank" to """<div class="book"><a href="/book/3">Top</a></div>""",
            "https://example.test/book/1" to """<div class="intro">Description</div><a class="toc" href="/toc">TOC</a>""",
            "https://example.test/toc" to """<li class="chapter"><a href="/chapter/1">One</a></li><a class="next" href="/toc2">Next</a>""",
            "https://example.test/toc2" to """<li class="chapter"><a href="/chapter/2">Two</a></li>""",
            "https://example.test/chapter/1" to """<div class="content">Part one</div><a class="next" href="/chapter/1?page=2">Next</a>""",
            "https://example.test/chapter/1?page=2" to """<div class="content">Part two</div><a class="next" href="/chapter/2">Next</a>""",
        )
        val fetched = mutableListOf<String>()
        val executor = object : LegadoHttpExecutor {
            override suspend fun execute(plan: LegadoRequestPlan): LegadoHttpResponse {
                fetched += plan.url
                return LegadoHttpResponse(plan.url, requireNotNull(documents[plan.url]) { "Unexpected URL: ${plan.url}" })
            }
        }
        val platform = DesktopLegadoScriptPlatform(
            client = OkHttpClient(),
            root = Files.createTempDirectory("legado-probe"),
            stores = MemoryPreferences.stores(),
        )
        val runtime = DesktopLegadoSourceRuntime(
            sources = { listOf(source) },
            createEngine = { RhinoJavaScriptEngine(platform) },
            createExecutor = { executor },
            variableStore = DesktopLegadoVariableStore(platform.store("legado_source_store")),
        )

        val work = runtime.getList(source.id, 0, null, SourceFilter(query = "novel")).single()
        val details = runtime.getDetails(work, SourceDetailsFetchMode.FORCE_REFRESH)
        val chapters = requireNotNull(details.chapters)
        val chapter = runtime.getChapterContent(chapters[0], chapters[1].url)

        assertEquals("First", details.title)
        assertEquals("Description", details.description)
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals("Part one\nPart two", chapter?.html)
        assertFalse("https://example.test/chapter/2" in fetched)

        // Discovery: categories without a URL are headings, the first real one is the default list.
        val categories = runtime.getFilterOptions(source.id).tagGroups.single().tags.toList()
        assertEquals(listOf("Latest", "Ranking"), categories.map { it.title })
        assertEquals(listOf("Newest"), runtime.getList(source.id, 0, null, null).map { it.title })
        assertEquals(listOf("Top"), runtime.getList(source.id, 0, null, SourceFilter(tags = setOf(categories[1]))).map { it.title })

        // The shared engine: rule bridge, Android's `java`/`source` objects, and what the desktop keeps out of reach.
        val engine = RhinoJavaScriptEngine(platform)
        val context = StandaloneLegadoRuleRuntimeContext(jsEngine = engine, source = source.config)
        val rule = AnalyzeRule("ignored", context, source.config.bookSourceUrl)
        assertEquals("abc", rule.getString("@js:java.put('token', 'abc'); java.get('token')"))
        assertEquals("aGk=", rule.getString("@js:java.base64Encode('hi')"))
        assertEquals("https://example.test", rule.getString("@js:source.getKey()"))
        assertEquals("kept", rule.getString("@js:source.setVariable('kept'); source.getVariable()"))
        val scripts = JavaScriptContext()
        assertEquals("x-y", engine.evaluate("String(new Packages.java.lang.StringBuilder('x').append('-y'))", scripts))
        listOf(
            "Packages.java.lang.Runtime.getRuntime().exec('true')",
            "new Packages.java.io.File('/').list()",
            "Packages.java.lang.Class.forName('java.lang.Runtime')",
            "java.getClass().getClassLoader()",
        ).forEach { escape ->
            assertEquals("blocked", engine.evaluate("try { $escape; 'ran' } catch (e) { 'blocked' }", scripts), escape)
        }
        DesktopLegadoRhino.timeoutMillis = 200
        assertThrows(DesktopLegadoScriptTimeout::class.java) { engine.execute("while (true) {}", scripts) }
        val looping = source.copy(config = source.config.copy(exploreUrl = "@js:while (true) {}"))
        val stuck = DesktopLegadoSourceRuntime({ listOf(looping) }, { RhinoJavaScriptEngine(platform) }, { executor })
        assertThrows(IllegalStateException::class.java) { runBlocking { stuck.getFilterOptions(looping.id) } }
        DesktopLegadoRhino.timeoutMillis = DesktopLegadoRhino.TIMEOUT_MS
        engine.dispose()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var received = ""
        server.createContext("/post") { exchange ->
            received = "${exchange.requestHeaders.getFirst("X-Source")}|${exchange.requestHeaders.getFirst("Content-Type")}|" +
                exchange.requestBody.bufferedReader().readText()
            val bytes = "response".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val executor = desktopLegadoExecutor(
                OkHttpClient(), source.config.copy(header = """{"X-Source":"catalog"}"""), variables = null,
            )
            val response = executor.execute(
                LegadoRequestPlan(
                    url = "http://127.0.0.1:${server.address.port}/post",
                    method = "POST",
                    body = "page=2",
                    bodyIsForm = true,
                    headers = mapOf("X-Source" to "request"),
                ),
            )
            assertEquals("response", response.body)
            val parts = received.split('|')
            assertEquals("request", parts[0])
            assertTrue(parts[1].startsWith("application/x-www-form-urlencoded"), received)
            assertEquals("page=2", parts[2])
        } finally {
            server.stop(0)
        }
    }
}

/** Script variables for the probes: one map per store name, nothing on disk. */
internal class MemoryPreferences : SourcePreferences {
    private val values = java.util.concurrent.ConcurrentHashMap<String, SourcePreferenceValue>()

    override fun snapshot(): Map<String, SourcePreferenceValue> = HashMap(values)

    override fun edit(edit: SourcePreferenceEdit): Boolean {
        if (edit.clear) values.clear()
        edit.changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        return true
    }

    override fun addListener(listener: org.skepsun.kototoro.core.source.SourcePreferenceListener) = Unit
    override fun removeListener(listener: org.skepsun.kototoro.core.source.SourcePreferenceListener) = Unit

    companion object {
        fun stores(): (String) -> SourcePreferences {
            val byName = java.util.concurrent.ConcurrentHashMap<String, MemoryPreferences>()
            return { name -> byName.getOrPut(name, ::MemoryPreferences) }
        }
    }
}
