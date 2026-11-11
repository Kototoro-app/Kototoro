package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.ReadingMode

class NovelReaderAutoScrollTest {

    @Test
    fun `scrollByPixels dispatches continuous scroll delta without mutating uiState`() {
        val viewModel = NovelComposeReaderViewModel()
        viewModel.publishChapter(
            chapterId = 10L,
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            content = "Paragraph 1\n\nParagraph 2",
            settings = NovelReaderSettings(readingMode = ReadingMode.SCROLL),
            translation = null,
        )
        var dispatched = 0f
        var canScrollForward = true
        viewModel.bindContinuousScrollDispatcher(
            dispatcher = { delta ->
                if (canScrollForward) {
                    dispatched += delta
                    delta
                } else {
                    0f
                }
            },
        )

        val stateBefore = viewModel.uiState.value
        assertTrue(viewModel.scrollByPixels(4))
        assertEquals(4f, dispatched, 0.0001f)
        assertSame(stateBefore, viewModel.uiState.value)

        canScrollForward = false
        assertFalse(viewModel.scrollByPixels(4))

        viewModel.bindContinuousScrollDispatcher(null)
        canScrollForward = true
        assertFalse(viewModel.scrollByPixels(4))
    }

    @Test
    fun `scrollByPixels returns false in paged mode so ScrollTimer uses page switch delay`() {
        val viewModel = NovelComposeReaderViewModel()
        viewModel.publishChapter(
            chapterId = 10L,
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            content = "Paragraph 1\n\nParagraph 2",
            settings = NovelReaderSettings(readingMode = ReadingMode.PAGED),
            translation = null,
        )
        viewModel.bindContinuousScrollDispatcher(dispatcher = { it })

        assertFalse(viewModel.scrollByPixels(4))
    }

    @Test
    fun `autoScroll sheet is mutually exclusive with other novel reader sheets`() {
        val viewModel = NovelComposeReaderViewModel()
        val settings = NovelReaderSettings(readingMode = ReadingMode.SCROLL)

        viewModel.publishChrome(enabled = true, controlsVisible = true, workTitle = "Book")
        viewModel.showSettings(settings)
        assertTrue(viewModel.uiState.value.settingsSheetVisible)

        viewModel.showAutoScroll()
        assertTrue(viewModel.uiState.value.autoScroll.visible)
        assertFalse(viewModel.uiState.value.controlsVisible)
        assertFalse(viewModel.uiState.value.settingsSheetVisible)

        viewModel.toggleAutoScroll()
        assertFalse(viewModel.uiState.value.autoScroll.visible)

        viewModel.toggleAutoScroll()
        assertTrue(viewModel.uiState.value.autoScroll.visible)

        viewModel.showTtsControls()
        assertFalse(viewModel.uiState.value.autoScroll.visible)
        assertTrue(viewModel.uiState.value.ttsControlsVisible)

        viewModel.showAutoScroll()
        assertTrue(viewModel.uiState.value.autoScroll.visible)
        assertFalse(viewModel.uiState.value.ttsControlsVisible)

        viewModel.dismissControlPanels()
        assertFalse(viewModel.uiState.value.autoScroll.visible)
    }

    @Test
    fun `autoScroll sheet marks hasOverlay when chrome is disabled`() {
        val viewModel = NovelComposeReaderViewModel()
        viewModel.publishChrome(enabled = false, controlsVisible = false, workTitle = "Book")
        assertFalse(viewModel.uiState.value.hasOverlay)

        viewModel.showAutoScroll()
        assertTrue(viewModel.uiState.value.hasOverlay)

        viewModel.dismissAutoScroll()
        assertFalse(viewModel.uiState.value.hasOverlay)
    }

    @Test
    fun `flushContinuousScrollPosition invokes bound flusher and clears on unbind`() {
        val viewModel = NovelComposeReaderViewModel()
        var flushed = 0
        viewModel.bindContinuousScrollDispatcher(
            dispatcher = { it },
            flushPosition = { flushed++ },
        )

        viewModel.flushContinuousScrollPosition()
        assertEquals(1, flushed)

        viewModel.bindContinuousScrollDispatcher(null)
        viewModel.flushContinuousScrollPosition()
        assertEquals(1, flushed)
    }
}
