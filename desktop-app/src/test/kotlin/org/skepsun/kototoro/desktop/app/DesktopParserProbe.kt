package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.parserhost.ParserSourceRuntime
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * kototoro / kotatsu / UMA plugin jars through the real session and UI, in two JVMs: the plugins, their source
 * settings, the favourite and the novel reading position written by the first process must be restored by the second.
 */
internal object DesktopParserProbe {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==",
    )

    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val writing = args[0] == "parsers-write"
        val fixtures = Path.of(args[9])
        val requested = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                requested += exchange.requestURI.path
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            start()
        }
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (writing) {
                for (name in listOf("kototoro", "kotatsu", "tsuki")) {
                    runBlocking { controller.importJar(fixtures.resolve("fixture-$name.jar")).join() }
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                // A private mirror: the plugin's own domain setting, persisted in the shared preference store.
                for (source in listOf("FIXTURE_KOTOTORO_ONLY", "FIXTURE_TSUKI_ONLY")) {
                    session.storage.preferences.open(ParserSourceRuntime.configNamespace(source)).edit(SourcePreferenceEdit(
                        changes = mapOf("domain" to SourcePreferenceValue.Text("127.0.0.1:${server.address.port}"))))
                }
            }
            check(session.startupErrors.isEmpty()) { session.startupErrors.joinToString() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.readerLoading.isEmpty() }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(name: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$name.png"), it.bytes) }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 4 }
                idle()
                if (writing) snapshot("sources")
                // Two plugins declare FIXTURE_SHARED; the alphabetically first id wins when no priority is configured.
                check(controller.state.value.sources.single { it.source.name == "FIXTURE_SHARED" }.source.locale == "ja")
                val novel = controller.state.value.sources.single { it.source.name == "FIXTURE_KOTOTORO_ONLY" }
                check(novel.source.contentType == "NOVEL" && novel.ecosystem.name == "KOTOTORO")
                check(controller.state.value.sources.single { it.source.name == "FIXTURE_TSUKI_ONLY" }.ecosystem.name == "UMA")

                if (writing) {
                    onNodeWithTag("source:FIXTURE_KOTOTORO_ONLY").assertTextContains("Kototoro", substring = true)
                    onNodeWithTag("source:FIXTURE_KOTOTORO_ONLY").assertTextContains("小说", substring = true)
                    onNodeWithTag("source:FIXTURE_KOTOTORO_ONLY").performClick(); idle()
                    check(controller.state.value.descriptor?.pagingMode == SourcePagingMode.OFFSET)
                    check(controller.state.value.descriptor?.isChapterContentSupported == true)
                    check(controller.state.value.items.map { it.id } == listOf(1000L, 1001L))
                    // Offset paging: the second batch continues after the two items shown, the third is past the end.
                    onNodeWithText("下一批").performClick(); idle()
                    check(controller.state.value.items.map { it.id } == listOf(1002L, 1003L) && controller.state.value.offset == 1)
                    onNodeWithText("下一批").performClick(); idle()
                    check(controller.state.value.items.isEmpty())
                    onNodeWithText("上一批").performClick(); idle()
                    check(controller.state.value.items.map { it.id } == listOf(1002L, 1003L))
                    // Parser sources expose their full sort list, not only Mihon's two buttons.
                    onNodeWithTag("source-sort").performClick()
                    onNodeWithTag("source-sort:RATING").performClick(); idle()
                    check(controller.state.value.browseOrder == "RATING")
                    check(controller.state.value.items.all { it.title.contains("RATING") })

                    // A novel: chapter text (not page images) in the novel reader, with its illustration.
                    onNodeWithTag("content:${controller.state.value.items.first().id}").performClick(); idle()
                    val book = controller.state.value.content!!
                    check(book.chapters.orEmpty().size == 2)
                    onAllNodesWithText("下载").assertCountEquals(0) // no page-image downloads for text chapters
                    runBlocking { controller.addFavourite().join() }
                    onNodeWithText("开始 / 继续阅读").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.NOVEL }
                    onNodeWithTag("novel-reader").assertExists()
                    onNodeWithText("Body of Chapter 1", substring = true).assertExists()
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("novel-image").fetchSemanticsNodes().isNotEmpty() }
                    check(requested.any { it.startsWith("/illustration/") }) { requested.toString() }
                    waitUntil(timeoutMillis = 15_000) { runBlocking { session.library.progress(book.id) } != null }
                    check(runBlocking { session.library.progress(book.id) }!!.chapterId == book.chapters!![0].id)
                    snapshot("novel-light")
                    // Reading settings persist; the chapter advances and the position follows it.
                    onNodeWithTag("novel-settings").performClick()
                    onNodeWithTag("novel-theme:DARK").performClick(); waitForIdle()
                    check(controller.state.value.novelSettings.theme == DesktopNovelTheme.DARK)
                    snapshot("novel-settings-dark")
                    onNodeWithTag("novel-settings").performClick()
                    onAllNodesWithText("下一章")[0].performClick(); idle()
                    onNodeWithText("Body of Chapter 2", substring = true).assertExists()
                    waitUntil(timeoutMillis = 15_000) {
                        runBlocking { session.library.progress(book.id) }?.chapterId == book.chapters!![1].id
                    }

                    // A manga-type UMA source still takes the page reader and its images come from the plugin's domain.
                    onNodeWithText("返回详情").performClick(); idle()
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source:FIXTURE_TSUKI_ONLY").performClick(); idle()
                    onNodeWithTag("content:${controller.state.value.items.first().id}").performClick(); idle()
                    val comic = controller.state.value.content!!
                    onNodeWithText("开始 / 继续阅读").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty() }
                    check(controller.state.value.pages.size == 3 && controller.state.value.readerImages.isNotEmpty())
                    check(requested.any { it.startsWith("/img/") }) { "page images must come from the plugin's own domain" }
                    check(runBlocking { session.library.progress(comic.id) } != null)
                } else {
                    onNodeWithTag("source:FIXTURE_KOTOTORO_ONLY").performClick(); idle()
                    check(controller.state.value.items.size == 2)
                    // The favourite resolves its source through the restored plugin again.
                    runBlocking { controller.library().join() }
                    val saved = controller.state.value.library.entries.single().content
                    check(saved.source.name == "FIXTURE_KOTOTORO_ONLY" && saved.id == 1000L) { saved.toString() }
                    check(runBlocking { session.library.isFavourite(saved.id) })
                    // Continue reading restores the chapter the first process left, with its saved reader theme.
                    check(controller.state.value.novelSettings.theme == DesktopNovelTheme.DARK)
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source:FIXTURE_KOTOTORO_ONLY").performClick(); idle()
                    onNodeWithTag("content:1000").performClick(); idle()
                    onNodeWithText("开始 / 继续阅读").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.NOVEL }
                    check(controller.state.value.novel!!.chapter.title == "Chapter 2")
                    onNodeWithText("Body of Chapter 2", substring = true).assertExists()
                    // Removing a parser plugin from the extension manager: its sources go, the others stay.
                    runBlocking { controller.showExtensions().join() }
                    idle()
                    val tsuki = controller.state.value.installedEntries.single { it.kind == DesktopInstalledKind.PARSER && it.ecosystem.name == "UMA" }
                    onNodeWithTag("extension-uninstall:${tsuki.id}").performClick(); idle()
                    onNodeWithTag("extension-uninstall-confirm").performClick(); idle()
                    check(controller.state.value.sources.none { it.source.name == "FIXTURE_TSUKI_ONLY" })
                    check(controller.state.value.sources.any { it.source.name == "FIXTURE_KOTOTORO_ONLY" })
                    check(session.storage.preferences.open("desktop_extensions").snapshot().keys.none { it.endsWith(tsuki.id) })
                }
            }
        } finally {
            runBlocking { controller.shutdown() }
            server.stop(0)
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
