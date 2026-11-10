package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Legado book source file is imported from the extensions flow, reaches the browse rail as a novel source of the
 * shared Legado runtime, lists its first discovery category with the categories as tags, reads a chapter through the
 * source protocol, and disappears again when removed. The site is a local HTTP server.
 */
internal object DesktopLegadoSourcesProbe {
    private const val NAME = "本地书源"

    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val root = Path.of(args[1])
        val pages = mapOf(
            "/latest?p=1" to """<div class="book"><a href="/book/1">最新小说</a></div>""",
            "/latest?p=2" to "<p>没有更多</p>",
            "/rank" to """<div class="book"><a href="/book/2">排行小说</a></div>""",
            "/book/1" to """<div class="intro">简介文字</div><a class="toc" href="/toc">目录</a>""",
            "/toc" to """<li class="chapter"><a href="/chapter/1">第一章</a></li><li class="chapter"><a href="/chapter/2">第二章</a></li>""",
            "/chapter/1" to """<div class="content">第一章的正文</div>""",
        )
        val agents = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
                val page = pages[exchange.requestURI.toString()]
                val body = (page ?: "missing").toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(if (page == null) 404 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val site = "http://127.0.0.1:${server.address.port}"
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1120, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                // One novel source and one audio source: only the novel source can run here.
                val file = root.resolve("legado.json")
                Files.writeString(file, """[
                    {"bookSourceName":"$NAME","bookSourceUrl":"$site","bookSourceType":0,
                     "header":"{\"X-Probe\":\"1\"}",
                     "searchUrl":"/search?q={{key}}","exploreUrl":"最新::/latest?p={{page}}\n排行::/rank\n分隔",
                     "ruleSearch":{"bookList":"class.book","name":"tag.a@text","bookUrl":"tag.a@href"},
                     "ruleBookInfo":{"intro":"class.intro@text","tocUrl":"class.toc@href"},
                     "ruleToc":{"chapterList":"class.chapter","chapterName":"tag.a@text","chapterUrl":"tag.a@href"},
                     "ruleContent":{"content":"@js:java.put('seen', 'yes'); result"}},
                    {"bookSourceName":"有声","bookSourceUrl":"https://audio.invalid","bookSourceType":1}
                ]""")
                controller.importExtensionFile(file); idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                val stored = controller.state.value.jsonSources.singleOrNull()
                check(stored != null && stored.name == NAME) { "expected one Legado source, got ${controller.state.value.jsonSources}" }
                check(controller.state.value.message.orEmpty().contains("1")) { controller.state.value.message.orEmpty() }
                val saved = (session.storage.preferences.open("desktop_json_sources").snapshot()["json_sources"]
                    as? SourcePreferenceValue.Text)?.value.orEmpty()
                check(saved.contains(stored.id)) { "the Legado source was not persisted: $saved" }
                val listing = controller.state.value.sources.firstOrNull { it.source.name == stored.id }
                check(listing != null) { "the Legado source is missing from the listings" }
                check(listing.ecosystem == SourceEcosystem.LEGADO && listing.source.contentType == "NOVEL") { listing.toString() }
                // A book source records no origin, so it is not offered as a refreshable repository.
                onNodeWithTag("extensions-tab:1").performClick(); idle()
                onNodeWithText("legado.json").assertDoesNotExist()

                // Browsing lists the first category that has an address and offers every category as a tag.
                onNodeWithTag("nav:浏览").performClick(); idle()
                onNodeWithTag("nav:浏览").performClick(); idle()
                onNodeWithTag("source-list").performScrollToNode(hasTestTag("source:${stored.id}"))
                onNodeWithTag("source:${stored.id}").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.run { items.isNotEmpty() || error != null } }
                idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                check(controller.state.value.items.map { it.title } == listOf("最新小说")) { controller.state.value.items.toString() }
                waitUntil(timeoutMillis = 20_000) { !controller.state.value.browseTagGroups.isNullOrEmpty() }
                val tags = controller.state.value.browseTagGroups.orEmpty().flatMap { it.tags }.map { it.title }
                check(tags == listOf("最新", "排行")) { tags.toString() }

                // Details, table of contents and the chapter text travel through the same source protocol as the UI's.
                val item = controller.state.value.items.single()
                val (details, text) = runBlocking {
                    val details = session.sources.getDetails(item, SourceDetailsFetchMode.FORCE_REFRESH)
                    val chapters = details.chapters.orEmpty()
                    details to session.sources.getChapterContent(chapters[0], chapters.getOrNull(1)?.url)?.html
                }
                check(details.description == "简介文字") { details.description.toString() }
                check(details.chapters.orEmpty().map { it.title } == listOf("第一章", "第二章")) { details.chapters.toString() }
                check(text.orEmpty().contains("第一章的正文")) { text.toString() }
                check(agents.all { it.startsWith("Mozilla/5.0") }) { agents.toString() }

                // Removing it drops both the stored document and the rail entry.
                onNodeWithTag("nav:更多").performClick(); idle()
                onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                onNodeWithTag("extensions-tab:0").performClick(); idle()
                onNodeWithTag("json-source-remove:${stored.id}").performClick(); idle()
                check(controller.state.value.jsonSources.isEmpty())
                check(controller.state.value.sources.none { it.ecosystem == SourceEcosystem.LEGADO })
            }
        } finally {
            runBlocking { controller.shutdown() }
            server.stop(0)
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
