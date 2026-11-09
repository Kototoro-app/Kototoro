package org.skepsun.kototoro.core.ui.chapters

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterGridColumnsTest {

    private val phoneWidth = 411.dp

    @Test
    fun `the old range keeps its phone columns`() {
        assertEquals(5, chapterGridColumns(phoneWidth, 1f))
        assertEquals(3, chapterGridColumns(phoneWidth, 1.5f))
    }

    @Test
    fun `the wider range reaches two and then one card per row on a phone`() {
        assertEquals(2, chapterGridColumns(phoneWidth, 2.5f))
        assertEquals(1, chapterGridColumns(phoneWidth, ChapterGridSizeRange.last / 100f))
    }

    @Test
    fun `a wide panel still gets several columns at the largest size`() {
        assertEquals(3, chapterGridColumns(1000.dp, ChapterGridSizeRange.last / 100f))
    }
}
