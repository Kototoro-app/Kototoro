package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
            runDesktopComposeUiTest(width = if (writing) 1260 else 920, height = if (writing) 850 else 620) {
                var fullscreen by mutableStateOf(false)
                setContent { DesktopApp(controller, fullscreen = fullscreen, onToggleFullscreen = { fullscreen = !fullscreen }) }
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
                fun novelKey(key: Key) {
                    onNodeWithTag("novel-reader").requestFocus()
                    onNodeWithTag("novel-reader").performKeyInput { pressKey(key) }
                    waitForIdle()
                }
                fun stableNovelChrome() {
                    val viewport = onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot
                    val first = controller.state.value.novel!!.firstVisible
                    val block = onNodeWithTag("novel-block:$first").fetchSemanticsNode().boundsInRoot
                    val requests = requested.size
                    onNodeWithTag("reader-back").assertIsDisplayed()
                    onNodeWithTag("reader-page-slider").assertIsDisplayed()
                    snapshot("novel-chrome")
                    novelKey(Key.H)
                    onNodeWithTag("reader-back").assertDoesNotExist()
                    onNodeWithTag("novel-reading-status").assertIsDisplayed()
                    check(onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot == viewport)
                    check(onNodeWithTag("novel-block:$first").fetchSemanticsNode().boundsInRoot == block)
                    check(controller.state.value.novel!!.firstVisible == first && requested.size == requests)
                    snapshot("novel-immersive")
                    onNodeWithTag("novel-reading-status").performClick(); waitForIdle()
                    onNodeWithTag("reader-back").assertIsDisplayed()
                    check(onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot == viewport)
                    onNodeWithTag("novel-list").performMouseInput { click(Offset(12f, height / 2f)) }
                    onNodeWithTag("novel-reading-status").assertIsDisplayed()
                    onNodeWithTag("reader-back").assertDoesNotExist()
                    onNodeWithTag("novel-reading-status").performClick(); waitForIdle()
                    onNodeWithTag("reader-back").assertIsDisplayed()
                    check(controller.state.value.novel!!.firstVisible == first)
                }
                fun novelDirectory() {
                    val before = controller.state.value.novel!!
                    val chapters = controller.state.value.content!!.chapters!!
                    val current = chapters.indexOfFirst { it.id == before.chapter.id }
                    val tag = "novel-directory-chapter:${before.chapter.id}:$current"
                    val requests = requested.size
                    val viewport = onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot
                    onNodeWithTag("reader-chapters").performClick(); waitForIdle()
                    onNodeWithTag("novel-chapters-panel").assertExists()
                    onNodeWithTag(tag).assertIsDisplayed().assertIsSelected()
                    snapshot("novel-chapters")
                    onNodeWithTag("novel-search-query").performTextInput("H")
                    onNodeWithTag("novel-search-query").performKeyInput { pressKey(Key.H) }
                    onNodeWithTag("reader-back").assertExists()
                    onNodeWithTag("novel-chapters-panel").assertExists()
                    onNodeWithTag("novel-search-query").performTextClearance()
                    onNodeWithTag("novel-search-query").performTextInput("not a chapter")
                    onNodeWithTag(tag).assertDoesNotExist()
                    onNodeWithTag("novel-directory-reverse").performClick(); waitForIdle()
                    onNodeWithTag("novel-directory-reverse").assertIsSelected()
                    onNodeWithTag("novel-directory-locate").performClick(); waitForIdle()
                    onNodeWithTag("novel-search-query").assert(
                        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
                    )
                    onNodeWithTag(tag).assertIsDisplayed().assertIsSelected()
                    // Choosing the current chapter closes the panel without fetching or resetting its body.
                    onNodeWithTag(tag).performClick(); idle()
                    onNodeWithTag("novel-chapters-panel").assertDoesNotExist()
                    check(controller.state.value.novel == before && requested.size == requests)
                    check(onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot == viewport)
                    onNodeWithTag("reader-chapters").performClick(); waitForIdle()
                    novelKey(Key.Escape)
                    onNodeWithTag("novel-chapters-panel").assertDoesNotExist()
                    check(controller.state.value.novel == before)
                }
                fun bookmarkPanel() {
                    onNodeWithTag("novel-bookmark-toggle").performTouchInput { longClick() }
                    waitForIdle()
                    onNodeWithTag("novel-bookmarks-panel").assertExists()
                }
                fun novelBookmarks() {
                    val content = controller.state.value.content!!
                    val bookmarks = controller.state.value.bookmarks
                    check(bookmarks.size == 2 && bookmarks.all { it.preview.isNotBlank() && it.preview.length <= 200 })
                    val first = bookmarks.single { it.chapterId == content.chapters!![0].id }
                    val second = bookmarks.single { it.chapterId == content.chapters!![1].id }
                    check(first.page == 12 && second.page == 16)
                    val viewport = onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot
                    bookmarkPanel()
                    snapshot("novel-bookmarks")
                    onNodeWithTag("novel-search-query").performTextInput("not a bookmark")
                    onNodeWithTag("novel-bookmark:${first.pageId}").assertDoesNotExist()
                    // Text editing must not run the reader's B shortcut.
                    onNodeWithTag("novel-search-query").performKeyInput { pressKey(Key.B) }
                    check(controller.state.value.bookmarks == bookmarks)
                    onNodeWithTag("novel-search-query").performTextClearance()
                    onNodeWithTag("novel-search-query").performTextInput("Chapter 1")
                    onNodeWithTag("novel-bookmark:${first.pageId}").assertIsDisplayed().performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 12 }
                    check(controller.state.value.novel!!.chapter.id == first.chapterId)
                    onNodeWithTag("novel-bookmarks-panel").assertDoesNotExist()
                    check(onNodeWithTag("novel-list").fetchSemanticsNode().boundsInRoot == viewport)
                    // Same-chapter jumps reuse the body, including repeated jumps to the same saved target.
                    val body = controller.state.value.novel!!.blocks
                    repeat(2) {
                        onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
                        waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 18 }
                        val requests = requested.size
                        bookmarkPanel()
                        onNodeWithTag("novel-bookmark:${first.pageId}").performClick(); idle()
                        waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 12 }
                        check(controller.state.value.novel!!.blocks === body && requested.size == requests)
                    }
                    // A separate card can be deleted without navigating or removing the other saved positions.
                    onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 18 }
                    novelKey(Key.B); idle()
                    val extra = controller.state.value.bookmarks.single { it.page == 18 }
                    bookmarkPanel()
                    onNode(hasTestTag("novel-bookmark-delete") and
                        hasAnyAncestor(hasTestTag("novel-bookmark:${extra.pageId}"))).performClick(); idle()
                    check(controller.state.value.bookmarks == bookmarks)
                    check(controller.state.value.novel!!.firstVisible == 18)
                    onNodeWithTag("novel-bookmark:${second.pageId}").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 16 }
                    check(controller.state.value.novel!!.chapter.id == second.chapterId)
                    check(runBlocking { session.library.bookmarks(content.id) } == bookmarks)
                }
                fun importedNovelBookmark() {
                    val content = controller.state.value.content!!
                    val chapters = content.chapters!!
                    val dao = session.storage.database.getBookmarksDao()
                    val first = controller.state.value.bookmarks.single { it.chapterId == chapters[0].id }
                    val original = runBlocking { dao.find(content.id, first.pageId) }!!
                    val html = "<p>${first.preview}</p><script>ignore()</script>"
                    val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
                    runBlocking { dao.upsert(listOf(original.copy(page = 700, imageUrl = "data:text/html;base64,$encoded"))) }
                    runBlocking { controller.read(chapters[1]).join() }; idle()
                    val imported = controller.state.value.bookmarks.single { it.pageId == first.pageId }
                    check(imported.page == 700)
                    bookmarkPanel()
                    onNodeWithTag("novel-search-query").performTextInput("Paragraph 11")
                    onNodeWithTag("novel-bookmark:${first.pageId}").assertTextContains(first.preview, substring = true)
                        .performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 12 }
                    check(controller.state.value.novel!!.chapter.id == first.chapterId)
                    onNode(hasContentDescription(AndroidStrings["bookmark_remove"]) and
                        hasAnyAncestor(hasTestTag("novel-bookmark-toggle"))).assertExists()
                    novelKey(Key.B); idle()
                    check(controller.state.value.bookmarks.none { it.pageId == first.pageId })
                    // Changed text fails without activating the chapter or falling back to the imported page number.
                    runBlocking { dao.upsert(listOf(original.copy(page = 700, imageUrl = "正文已经变化无法匹配"))) }
                    runBlocking { controller.read(chapters[1]).join() }; idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 0 }
                    val before = controller.state.value.novel!!
                    val changed = controller.state.value.bookmarks.single { it.pageId == first.pageId }
                    runBlocking { controller.openNovelBookmark(changed).join() }; waitForIdle()
                    check(controller.state.value.error?.contains("书签正文") == true)
                    val after = controller.state.value.novel!!
                    check(after.chapter == before.chapter && after.blocks === before.blocks &&
                        after.startBlock == before.startBlock && after.navigation == before.navigation &&
                        after.firstVisible == before.firstVisible) {
                        "Failed bookmark moved reader: ${before.firstVisible}/${before.startBlock}/${before.navigation} -> " +
                            "${after.firstVisible}/${after.startBlock}/${after.navigation}"
                    }
                    runBlocking { dao.upsert(listOf(original)); controller.read(chapters[1]).join() }; idle()
                    onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(16f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 16 }
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
                    onNodeWithTag("preview-read").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.NOVEL }
                    onNodeWithTag("novel-reader").assertExists()
                    onNodeWithText("Body of Chapter 1", substring = true).assertExists()
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("novel-image").fetchSemanticsNodes().isNotEmpty() }
                    check(requested.any { it.startsWith("/illustration/") }) { requested.toString() }
                    waitUntil(timeoutMillis = 15_000) { runBlocking { session.library.progress(book.id) } != null }
                    check(runBlocking { session.library.progress(book.id) }!!.chapterId == book.chapters!![0].id)
                    snapshot("novel-light")
                    stableNovelChrome()
                    onNodeWithTag("reader-previous-chapter").assertIsNotEnabled()
                    onNodeWithTag("reader-next-chapter").assertIsEnabled()
                    onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 12 }
                    onNodeWithTag("novel-block:12").assertIsDisplayed()
                    waitUntil(timeoutMillis = 15_000) { runBlocking { session.library.progress(book.id) }?.page == 12 }
                    snapshot("novel-slider")
                    novelDirectory()
                    check(controller.state.value.novel!!.firstVisible == 12)
                    bookmarkPanel()
                    onNodeWithText(AndroidStrings["no_bookmarks_yet"]).assertIsDisplayed()
                    novelKey(Key.Escape)
                    check(controller.state.value.novel!!.firstVisible == 12)
                    onNodeWithTag("novel-bookmark-toggle").performClick(); idle()
                    check(controller.state.value.bookmarks.single().page == 12)
                    bookmarkPanel()
                    snapshot("novel-bookmarks-light")
                    novelKey(Key.Escape)
                    // Reading settings persist; the chapter advances and the position follows it.
                    onNodeWithTag("reader-options").performClick()
                    onNodeWithTag("novel-theme:DARK").performClick(); waitForIdle()
                    check(controller.state.value.novelSettings.theme == DesktopNovelTheme.DARK)
                    onNode(hasText("+") and hasAnyAncestor(hasTestTag("novel-font-size"))).performClick(); waitForIdle()
                    onNode(hasText("+") and hasAnyAncestor(hasTestTag("novel-line-spacing"))).performClick(); waitForIdle()
                    onNode(hasText("+") and hasAnyAncestor(hasTestTag("novel-width"))).performClick(); waitForIdle()
                    onNodeWithTag("novel-serif").performClick(); waitForIdle()
                    check(controller.state.value.novelSettings == DesktopNovelSettings(
                        fontSize = 20, lineSpacing = 1.8f, width = 780, theme = DesktopNovelTheme.DARK, serif = true,
                    ))
                    check(controller.state.value.novel!!.chapter.id == book.chapters!![0].id)
                    snapshot("novel-settings-dark")
                    onNodeWithText(AndroidStrings["novel_reader_tab_reading"]).performClick(); waitForIdle()
                    onNodeWithTag("novel-fullscreen").assertIsDisplayed().performClick(); waitForIdle()
                    check(fullscreen)
                    onNodeWithTag("novel-reader").requestFocus()
                    onNodeWithTag("novel-reader").performKeyInput { pressKey(Key.Escape) }; waitForIdle()
                    onNodeWithTag("novel-settings-panel").assertDoesNotExist()
                    check(controller.state.value.screen == DesktopScreen.NOVEL)
                    onNodeWithTag("novel-reader").performKeyInput { pressKey(Key.F11) }; waitForIdle()
                    check(!fullscreen)
                    onNodeWithTag("reader-next-chapter").performClick(); idle()
                    onNodeWithText("Body of Chapter 2", substring = true).assertExists()
                    waitUntil(timeoutMillis = 15_000) {
                        runBlocking { session.library.progress(book.id) }?.chapterId == book.chapters!![1].id
                    }
                    onNodeWithTag("reader-previous-chapter").assertIsEnabled()
                    onNodeWithTag("reader-next-chapter").assertIsNotEnabled()
                    onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(16f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 16 }
                    waitUntil(timeoutMillis = 15_000) { runBlocking { session.library.progress(book.id) }?.page == 16 }
                    novelKey(Key.B); idle()
                    novelBookmarks()
                    importedNovelBookmark()

                    // A manga-type UMA source still takes the page reader and its images come from the plugin's domain.
                    onNodeWithTag("reader-back").performClick(); idle()
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source:FIXTURE_TSUKI_ONLY").performClick(); idle()
                    onNodeWithTag("content:${controller.state.value.items.first().id}").performClick(); idle()
                    val comic = controller.state.value.content!!
                    onNodeWithTag("preview-read").performClick(); idle()
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
                    check(controller.state.value.novelSettings == DesktopNovelSettings(
                        fontSize = 20, lineSpacing = 1.8f, width = 780, theme = DesktopNovelTheme.DARK, serif = true,
                    ))
                    onNodeWithTag("nav:浏览").performClick(); idle()
                    onNodeWithTag("source:FIXTURE_KOTOTORO_ONLY").performClick(); idle()
                    onNodeWithTag("content:1000").performClick(); idle()
                    onNodeWithTag("preview-read").performClick(); idle()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.NOVEL }
                    check(controller.state.value.novel!!.chapter.title == "Chapter 2")
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.novel!!.firstVisible == 16 }
                    onNodeWithTag("novel-block:16").assertIsDisplayed()
                    stableNovelChrome()
                    novelDirectory()
                    novelBookmarks()
                    novelKey(Key.B); idle()
                    check(controller.state.value.bookmarks.size == 1)
                    novelKey(Key.B); idle()
                    check(controller.state.value.bookmarks.size == 2)
                    // The shared panel also fits the smaller window after a real process restart.
                    onNodeWithTag("reader-options").performClick(); waitForIdle()
                    onNodeWithTag("novel-serif").assertIsDisplayed().assertIsOn()
                    onNodeWithTag("novel-width").assertIsDisplayed()
                    snapshot("novel-settings-narrow")
                    onNodeWithTag("novel-reader").requestFocus()
                    onNodeWithTag("novel-reader").performKeyInput { pressKey(Key.Escape) }; waitForIdle()
                    // Removing a parser plugin from the extension manager: its sources go, the others stay.
                    runBlocking { controller.showExtensions().join() }
                    idle()
                    val tsuki = controller.state.value.installedEntries.single { it.kind == DesktopInstalledKind.PARSER && it.ecosystem.name == "UMA" }
                    onNodeWithTag("installed-list").performScrollToNode(hasTestTag("extension-uninstall:${tsuki.id}"))
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
