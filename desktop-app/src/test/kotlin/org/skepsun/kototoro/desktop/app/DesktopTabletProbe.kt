package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

/** Exercises authored reader pages through the real SDK, UI and database; never opens user data. */
internal object DesktopTabletProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val narrow = args[0] == "tablet-narrow"
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = if (narrow) 920 else 1260, height = if (narrow) 620 else 850) {
                var fullscreen by mutableStateOf(false)
                setContent {
                    DesktopApp(controller, fullscreen = fullscreen, onToggleFullscreen = { fullscreen = !fullscreen })
                }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(label: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                idle()
                val rail = onNodeWithTag("tablet-navigation").fetchSemanticsNode().boundsInRoot
                check(rail.width == 80f)
                onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.isNotEmpty() }; idle()
                snapshot("browse")
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick(); idle()
                onNodeWithTag(if (narrow) "details-stacked" else "details-two-pane").assertExists()
                snapshot("details")
                onNodeWithText("开始 / 继续阅读").performClick(); idle()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("reader-page").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("tablet-navigation").assertDoesNotExist()
                onNodeWithTag("source-pane").assertDoesNotExist()
                val progress = runBlocking { session.library.progress(content.id) }
                val requests = System.getProperty("fixture.reader.requests")
                val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                check(viewport.left == 0f)
                snapshot("reader")
                onNodeWithTag("reader-hide-controls").performClick(); idle()
                val hiddenViewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                check(hiddenViewport.height > viewport.height + 100f)
                check(System.getProperty("fixture.reader.requests") == requests)
                check(runBlocking { session.library.progress(content.id) } == progress)
                snapshot("immersive")
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.H) }; idle()
                onNodeWithTag("reader-page-slider").assertExists()
                onNodeWithTag("reader-fullscreen").performClick(); idle(); check(fullscreen)
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.F11) }; idle(); check(!fullscreen)
                onNodeWithTag("reader-page-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.pageIndex == 3 && !controller.state.value.busy
                }; idle()
                val sliderProgress = runBlocking { session.library.progress(content.id) }
                check(sliderProgress?.page == 3)
                onNodeWithTag("reader-bookmark-toggle").performClick(); idle()
                val bookmark = controller.state.value.bookmarks.single()
                check(bookmark.page == 3 && bookmark.pageId == controller.state.value.pages[3].id)
                check(runBlocking { session.library.progress(content.id) } == sliderProgress)
                onNodeWithTag("reader-bookmarks").performClick(); idle()
                snapshot("bookmarks")
                onNodeWithTag("reader-panel-close").performClick(); idle()
                onNodeWithTag("reader-options").performClick(); idle()
                snapshot("options")
                onNodeWithTag("reader-panel-close").performClick(); idle()
                onNodeWithTag("reader-chapters").performClick(); idle()
                onNodeWithTag("reader-chapter-query").performTextInput("H")
                onNodeWithTag("reader-chapter-query").performKeyInput { pressKey(Key.H) }
                onNodeWithTag("reader-panel").assertExists()
                check(controller.state.value.pageIndex == 3)
                onNodeWithTag("reader-chapter-query").performTextClearance()
                snapshot("chapters")
                val next = controller.state.value.adjacentChapter(true)!!
                onNodeWithTag("reader-chapter:${next.id}").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.chapter?.id == next.id && !controller.state.value.busy
                }; idle()
                onNodeWithTag("reader-panel").assertDoesNotExist()
                check(controller.state.value.pageIndex == 0)
                onNodeWithTag("reader-bookmarks").performClick(); idle()
                onNodeWithTag("reader-bookmark:${bookmark.pageId}").performClick(); idle()
                check(controller.state.value.chapter?.id == bookmark.chapterId && controller.state.value.pageIndex == 3)
                onNodeWithTag("reader-bookmark-toggle").performClick(); idle()
                check(controller.state.value.bookmarks.isEmpty())
                onNodeWithTag("reader-fullscreen").performClick(); idle(); check(fullscreen)
                onNodeWithText("返回详情").performClick(); idle(); check(!fullscreen)
                onNodeWithTag("tablet-navigation").assertExists()
                onNodeWithText("加入收藏").performClick(); idle()
                onNodeWithTag("nav:收藏").performClick(); idle()
                onNodeWithTag("content:${content.id}").assertExists()
                onNodeWithTag("library-query").performTextInput("no-match"); idle()
                onNodeWithTag("content:${content.id}").assertDoesNotExist()
                onNodeWithTag("library-clear-empty").performClick(); idle()
                onNodeWithTag("library-filters").performClick(); idle()
                onNodeWithTag("library-reading:UNREAD").performClick(); idle()
                onNodeWithTag("content:${content.id}").assertDoesNotExist()
                snapshot("library-filter")
                onNodeWithTag("library-filter-reset").performClick(); idle()
                onNodeWithTag("library-filter-scrollbar").assertExists()
                val category = controller.state.value.library.categories.single()
                onNodeWithTag("library-category:${category.id}").performScrollTo().performClick(); idle()
                onNodeWithTag("library-source:${content.source.name}").performScrollTo().performClick(); idle()
                check(controller.state.value.librarySelection.filterCount == 2)
                onNodeWithTag("content:${content.id}").assertExists()
                onNodeWithTag("library-filter-reset").performClick(); idle()
                onNodeWithTag("library-filter-close").performClick(); idle()
                onNodeWithTag("content:${content.id}").assertExists()
                val beforeQuery = System.getProperty("fixture.reader.requests")
                onNodeWithTag("library-query").performTextInput(content.title); idle()
                onNodeWithTag("nav:历史").performClick(); idle()
                check(onNodeWithTag("library-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "")
                onNodeWithTag("nav:收藏").performClick(); idle()
                check(onNodeWithTag("library-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == content.title)
                check(System.getProperty("fixture.reader.requests") == beforeQuery)
                snapshot("library")
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
