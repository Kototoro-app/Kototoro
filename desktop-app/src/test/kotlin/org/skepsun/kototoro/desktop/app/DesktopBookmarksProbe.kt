package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.math.abs

/** Separate JVMs verify the real SDK/cache, shared bookmark table and continuous position restoration. */
internal object DesktopBookmarksProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val reading = mode.endsWith("-read")
        // The shelf run is the same write as the plain one: it saves one position and the next process visits it.
        val shelf = mode.startsWith("bookmarks-shelf")
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (!reading) runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun ready(page: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.readerScrollReady && controller.state.value.pageIndex == page &&
                            !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    idle()
                }
                if (reading && shelf) {
                    // Android reaches its bookmarks from the home action and from the rail; the home tile is the
                    // entry that exists without editing the navigation, so it is the one clicked here.
                    System.setProperty("fixture.reader.offline", "true")
                    runBlocking { controller.home().join() }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.HOME }
                    idle()
                    onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("home-quick:3"))
                    onNodeWithTag("home-quick:3").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.BOOKMARKS }
                    idle()
                    val group = controller.state.value.bookmarkGroups.single()
                    val bookmark = group.bookmarks.single()
                    check(group.content.title.isNotBlank() && bookmark.chapterTitle.isNotBlank()) {
                        "the shelf names the work and the saved chapter"
                    }
                    onNodeWithTag("bookmark-group:${group.content.id}").assertExists()
                    onNodeWithTag("bookmark-row:${bookmark.pageId}").assertExists()
                    onNodeWithTag("bookmark-check:${bookmark.pageId}", useUnmergedTree = true).assertExists()
                    // Android opens a saved position from the shelf incognito, so revisiting it records nothing.
                    // The write process read the chapter normally, so the row exists and must stay exactly as it was.
                    val recorded = runBlocking { session.library.progress(group.content.id) }
                    onNodeWithTag("bookmark-row:${bookmark.pageId}").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER }
                    ready(bookmark.page)
                    check(controller.state.value.readerIncognito) { "Android's shelf jump is incognito" }
                    check(controller.state.value.chapter?.id == bookmark.chapterId)
                    check(abs(controller.state.value.readerScroll - bookmark.scroll) < 2)
                    check(runBlocking { session.library.progress(group.content.id) } == recorded) {
                        "the incognito shelf jump must not write the history"
                    }
                    runBlocking { controller.backToDetails().join() }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    idle()
                    runBlocking { controller.dismissDetails().join() }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.BOOKMARKS }
                    idle()
                    // Android's selection bar: SELECT ALL then REMOVE, one message afterwards.
                    onNodeWithTag("bookmarks-select-all").performClick()
                    idle()
                    check(controller.state.value.bookmarkShelfSelection == setOf(bookmark.pageId))
                    onNodeWithTag("bookmarks-select-delete").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.bookmarkGroups.isEmpty() }
                    idle()
                    check(controller.state.value.message == AndroidStrings["bookmarks_removed"])
                    onNodeWithTag("bookmarks-empty").assertExists()
                    check(runBlocking { session.library.bookmarkGroups() }.isEmpty())
                    return@runDesktopComposeUiTest
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick(); idle()
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick(); idle()
                if (reading) System.setProperty("fixture.reader.offline", "true")
                onNodeWithTag("preview-read").performClick(); idle()
                if (!reading) {
                    runBlocking {
                        controller.readerSettings(controller.state.value.readerSettings.copy(
                            mode = DesktopReaderMode.CONTINUOUS)).join()
                    }
                    ready(0)
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(3); ready(3)
                    onNodeWithTag("reader-scroll-list").performMouseInput { moveTo(center); scroll(5f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScroll > 20f }; idle()
                    val offset = controller.state.value.readerScroll
                    onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.B) }; idle()
                    val bookmark = controller.state.value.bookmarks.single()
                    check(bookmark.page == 3 && abs(bookmark.scroll - offset) < 2)
                    onNodeWithTag("reader-next-chapter").performClick(); ready(0)
                    check(controller.state.value.chapter?.id != bookmark.chapterId)
                } else {
                    ready(0)
                    val bookmark = controller.state.value.bookmarks.single()
                    check(bookmark.scroll > 20 && controller.state.value.chapter?.id != bookmark.chapterId)
                    // Android: a long press on the bookmark button opens the bookmarks.
                    onNodeWithTag("reader-bookmark-toggle").performTouchInput { longClick() }; idle()
                    onNodeWithTag("reader-bookmark:${bookmark.pageId}").performClick(); ready(3)
                    check(controller.state.value.chapter?.id == bookmark.chapterId)
                    check(abs(controller.state.value.readerScroll - bookmark.scroll) < 2)
                    check(System.getProperty("fixture.reader.requests", "0") == "0")
                    onNodeWithTag("reader-bookmark-toggle").performClick(); idle()
                    check(controller.state.value.bookmarks.isEmpty())
                    check(runBlocking { session.library.bookmarks(content.id) }.isEmpty())
                }
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=$mode")
    }
}
