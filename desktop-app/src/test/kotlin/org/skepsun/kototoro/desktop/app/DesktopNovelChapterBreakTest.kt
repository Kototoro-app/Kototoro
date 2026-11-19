package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.desktop.runtime.NovelRun
import java.nio.file.Path

/**
 * Android's `reader_chapter_break` in the Windows novel reader: off, the chapter ends with the previous/next buttons
 * (paged mode reads on into the next chapter by itself); on, the shared chapter break card (information only, no
 * button) marks the end of the chapter: a low band above the chapter buttons when scrolling, a page of its own after
 * the last page when paged. Chapters still run on as before; the card only adds a stop in between.
 *
 * The reader is composed with a hand-built state whose controller has no chapters to move to, so these tests pin what
 * the reader shows and when it shows it; the chapter change itself is covered by the continuous/paged reader probe.
 */
class DesktopNovelChapterBreakTest {
    @TempDir lateinit var directory: Path

    private val source = SourceRef("FIXTURE_NOVEL", "en", "NOVEL")

    private fun chapter(id: Long) = SourceChapter(id, "Chapter $id", id.toFloat(), 0, "/c/$id", null, 0L, null, source)

    private fun state(current: Long, chapterBreak: Boolean, paged: Boolean): DesktopAppState {
        val chapters = listOf(chapter(1), chapter(2))
        val content = SourceContent(
            id = 1, title = "Book", altTitles = emptySet(), url = "/b", publicUrl = "/b", rating = 0f,
            contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
            source = source, chapters = chapters,
        )
        val blocks = List(80) { NovelBlock.Paragraph(listOf(NovelRun("Paragraph $it of the chapter, long enough to wrap.", false, false))) }
        return DesktopAppState(
            screen = DesktopScreen.NOVEL,
            content = content,
            chapter = chapters.first { it.id == current },
            novel = DesktopNovel(chapters.first { it.id == current }, blocks, emptyMap(), startBlock = 0),
            readerSettings = DesktopReaderSettings(chapterBreak = chapterBreak),
            novelSettings = DesktopNovelSettings(paged = paged),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    private fun withReader(
        controller: DesktopController,
        initial: DesktopAppState,
        block: androidx.compose.ui.test.ComposeUiTest.(set: (DesktopAppState) -> Unit) -> Unit,
    ) {
        runDesktopComposeUiTest(width = 920, height = 620) {
            var state by mutableStateOf(initial)
            setContent { DesktopTheme { DesktopNovelReader(controller, state, closing = false) } }
            waitForIdle()
            block { state = it }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.hasBreak() =
        onAllNodesWithTag("novel-chapter-break").fetchSemanticsNodes().isNotEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key) {
        onNodeWithTag("novel-reader").requestFocus()
        onNodeWithTag("novel-reader").performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `novel reader shows the chapter break card from the setting`() {
        // One compatibility platform per JVM: every scenario shares this session.
        val session = runBlocking { DesktopSession.open(directory.resolve("novel break")) }
        val controller = DesktopController(session)
        try {
            scrollReaderEndsWithTheCardOnlyWhenTheSettingIsOn(controller)
            pagedReaderOpensTheCardAfterTheLastPage(controller)
            pagedReaderWithoutTheSettingShowsNoCard(controller)
        } finally {
            runBlocking { controller.shutdown() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun scrollReaderEndsWithTheCardOnlyWhenTheSettingIsOn(controller: DesktopController) {
        withReader(controller, state(current = 1, chapterBreak = false, paged = false)) { set ->
            onNodeWithTag("novel-list").performScrollToIndex(80)
            waitForIdle()
            assertTrue(!hasBreak())
            onNodeWithTag("novel-next-chapter").assertExists()
            set(state(current = 1, chapterBreak = true, paged = false))
            waitForIdle()
            onNodeWithTag("novel-list").performScrollToIndex(80)
            waitForIdle()
            assertTrue(hasBreak())
            // The card is information only: the chapter buttons stay, and it has no button of its own.
            onNodeWithTag("novel-next-chapter").assertExists()
            assertTrue(onAllNodesWithText(AndroidStrings["chapter_break_continue"]).fetchSemanticsNodes().isEmpty())
            set(state(current = 2, chapterBreak = true, paged = false))
            waitForIdle()
            onNodeWithTag("novel-list").performScrollToIndex(80)
            waitForIdle()
            assertTrue(hasBreak())
            assertTrue(onAllNodesWithText(AndroidStrings["chapter_break_continue"]).fetchSemanticsNodes().isEmpty())
            assertEquals(1, onAllNodesWithText(AndroidStrings["chapter_break_last"]).fetchSemanticsNodes().size)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun pagedReaderOpensTheCardAfterTheLastPage(controller: DesktopController) {
        withReader(controller, state(current = 1, chapterBreak = true, paged = true)) { _ ->
            onNodeWithTag("novel-paged").assertExists()
            assertTrue(!hasBreak())
            var turns = 0
            while (!hasBreak() && turns < 200) { press(Key.PageDown); turns++ }
            assertTrue(hasBreak()) { "the card must open after the last page ($turns turns)" }
            assertTrue(turns > 1) { "the chapter must span several pages" }
            // The card is a page, not a control: no button, and the reader stays on it until it is turned away from
            // (here the controller has no next chapter to read on into).
            assertTrue(onAllNodesWithText(AndroidStrings["chapter_break_continue"]).fetchSemanticsNodes().isEmpty())
            onNodeWithTag("novel-reader").assertExists()
            // Turning back closes the card and shows the last page again; forward opens it once more.
            press(Key.PageUp)
            assertTrue(!hasBreak())
            onNodeWithTag("novel-paged").assertExists()
            press(Key.PageDown)
            assertTrue(hasBreak())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun pagedReaderWithoutTheSettingShowsNoCard(controller: DesktopController) {
        withReader(controller, state(current = 1, chapterBreak = false, paged = true)) { _ ->
            repeat(30) { press(Key.PageDown) }
            assertTrue(!hasBreak())
        }
    }
}
