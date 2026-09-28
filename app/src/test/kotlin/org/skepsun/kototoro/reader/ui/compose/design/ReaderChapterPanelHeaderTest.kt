package org.skepsun.kototoro.reader.ui.compose.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderChapterPanelHeaderTest {

    @Test
    fun `subtitle joins the non-blank parts with dots`() {
        assertEquals(
            "Chapter 01 · Ch. 1/3 Pg. 1/8 · 4%",
            readerChapterPanelSubtitle(listOf("Chapter 01", "Ch. 1/3 Pg. 1/8", "4%")),
        )
    }

    @Test
    fun `subtitle skips blank and repeated parts`() {
        assertEquals("Chapter 01 · 2/3", readerChapterPanelSubtitle(listOf("Chapter 01", "", null, "Chapter 01", "2/3")))
        assertEquals("", readerChapterPanelSubtitle(listOf(null, " ")))
    }
}
