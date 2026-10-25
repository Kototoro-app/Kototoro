package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

/** Actual extension/controller/reader path, including a long directory and a null default branch. */
internal object DesktopMangaDirectoryProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        System.setProperty("fixture.reader.directory", "true")
        val narrow = args[0].endsWith("narrow")
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = if (narrow) 520 else 1260, height = if (narrow) 620 else 850) {
                setContent { DesktopApp(controller) }
                fun ready(number: Int, page: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        val state = controller.state.value
                        !state.busy && state.error == null && state.chapter?.number == number.toFloat() &&
                            state.pageIndex == page && state.image != null && state.readerLoading.isEmpty()
                    }
                    waitForIdle()
                }
                fun snapshot(label: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            Files.write(Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png"), it.bytes)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.items.size == 1 }
                val work = controller.state.value.items.single()
                onNodeWithTag("content:${work.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.content?.chapters?.size == 121 }
                val chapters = controller.state.value.content!!.chapters!!
                val current = chapters.single { it.number == 84f }
                val next = chapters.single { it.number == 79f }
                val alternate = chapters.single { it.branch == "other branch" }
                runBlocking { controller.read(current).join() }
                ready(84, 0)
                runBlocking { controller.page(1).join() }
                ready(84, 1)
                runBlocking { controller.toggleBookmark().join() }
                waitUntil(timeoutMillis = 15_000) { runBlocking { session.library.progress(work.id) }?.page == 1 }
                val progress = runBlocking { session.library.progress(work.id) }
                val navigation = controller.state.value.readerNavigation
                onNodeWithTag("reader-chapters").performClick()
                onNodeWithTag("reader-chapter:${current.id}").assertIsDisplayed()
                onNodeWithContentDescription("Bookmarked", useUnmergedTree = true).assertExists()
                onNodeWithTag("chapter-directory-list").performScrollToIndex(0)
                onNodeWithTag("reader-chapter:${current.id}").assertDoesNotExist()
                onNodeWithTag("reader-chapter-locate").performClick()
                onNodeWithTag("reader-chapter:${current.id}").assertIsDisplayed()
                val before = onNodeWithTag("chapter-directory-list").fetchSemanticsNode().config[
                    SemanticsProperties.VerticalScrollAxisRange].value()
                onNodeWithTag("chapter-directory-list").performMouseInput {
                    moveTo(center); press()
                    repeat(6) { advanceEventTime(16); moveBy(Offset(0f, -20f)) }
                    release()
                }
                waitForIdle()
                check(onNodeWithTag("chapter-directory-list").fetchSemanticsNode().config[
                    SemanticsProperties.VerticalScrollAxisRange].value() > before)
                onNodeWithTag("reader-panel").assertExists()
                check(controller.state.value.readerNavigation == navigation && controller.state.value.pageIndex == 1)
                val wheelBefore = onNodeWithTag("chapter-directory-list").fetchSemanticsNode().config[
                    SemanticsProperties.VerticalScrollAxisRange].value()
                // A mouse wheel and a trackpad's two-finger scroll are scroll input without a press: they must
                // scroll the chapter list instead of dragging the panel away (issue #601).
                onNodeWithTag("chapter-directory-list").performMouseInput { moveTo(center); scroll(4f) }
                waitForIdle()
                check(onNodeWithTag("chapter-directory-list").fetchSemanticsNode().config[
                    SemanticsProperties.VerticalScrollAxisRange].value() > wheelBefore) { "the wheel must scroll" }
                onNodeWithTag("reader-panel").assertExists()
                check(controller.state.value.readerNavigation == navigation && controller.state.value.pageIndex == 1)
                onNodeWithTag("chapter-directory-list").performMouseInput { moveTo(center); scroll(-12f) }
                waitForIdle()
                onNodeWithTag("reader-panel").assertExists()
                check(controller.state.value.pageIndex == 1)
                onNodeWithTag("reader-chapter-locate").performClick()
                snapshot("list")
                onNodeWithTag("reader-chapter-branch:other branch").performClick()
                onNodeWithTag("reader-chapter:${alternate.id}").assertIsDisplayed()
                onNodeWithTag("reader-chapter:${current.id}").assertDoesNotExist()
                onNodeWithTag("reader-chapter-locate").performClick()
                onNodeWithTag("reader-chapter:${current.id}").assertIsDisplayed()
                onNodeWithTag("reader-chapter-branch:default").assertIsSelected()
                onNodeWithTag("reader-chapter-reverse").performClick()
                onNodeWithTag("reader-chapter:${current.id}").assertIsDisplayed()
                val previous = chapters.single { it.number == 83f }
                check(onNodeWithTag("reader-chapter:${current.id}").fetchSemanticsNode().boundsInRoot.top <
                    onNodeWithTag("reader-chapter:${previous.id}").fetchSemanticsNode().boundsInRoot.top)
                onNodeWithTag("reader-chapter-query").performTextInput("no match")
                onNodeWithTag("reader-chapter-query").performKeyInput { pressKey(Key.H) }
                onNodeWithTag("reader-panel").assertExists()
                check(controller.state.value.pageIndex == 1)
                onNodeWithTag("reader-chapter:${current.id}").assertDoesNotExist()
                onNodeWithTag("reader-chapter-locate").performClick()
                onNodeWithTag("reader-chapter-query").assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                onNodeWithTag("reader-chapter:${current.id}").assertIsDisplayed()
                onNodeWithTag("reader-chapter-grid").performClick()
                onNodeWithTag("reader-chapter-grid").assertIsOn()
                onNodeWithTag("reader-chapter:${current.id}").assertIsDisplayed()
                onNodeWithContentDescription("Current", useUnmergedTree = true).assertExists()
                snapshot("grid")
                onNodeWithTag("reader-chapter:${current.id}").performClick()
                onNodeWithTag("reader-panel").assertDoesNotExist()
                check(controller.state.value.readerNavigation == navigation && controller.state.value.pageIndex == 1)
                check(runBlocking { session.library.progress(work.id) } == progress)
                onNodeWithTag("reader-chapters").performClick()
                onNodeWithTag("reader-chapter-query").performTextInput("Chapter 79")
                onNodeWithTag("reader-chapter:${next.id}").assertIsDisplayed().performClick()
                ready(79, 0)
                onNodeWithTag("reader-panel").assertDoesNotExist()
                check(controller.state.value.readerNavigation > navigation)
                onNodeWithTag("reader-chapters").performClick()
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.Escape) }
                onNodeWithTag("reader-panel").assertDoesNotExist()
                check(controller.state.value.screen == DesktopScreen.READER && controller.state.value.chapter == next)
            }
        } finally {
            System.clearProperty("fixture.reader.directory")
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
        println("NETWORK_REQUESTS=0")
    }
}
