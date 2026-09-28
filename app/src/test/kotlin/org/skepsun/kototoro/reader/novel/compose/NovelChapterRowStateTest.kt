package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelChapterRowStateTest {

    @Test
    fun `rows before the current chapter are read, after are unread`() {
        assertEquals(NovelChapterRowState.READ, novelChapterRowState(index = 0, currentIndex = 2))
        assertEquals(NovelChapterRowState.CURRENT, novelChapterRowState(index = 2, currentIndex = 2))
        assertEquals(NovelChapterRowState.UNREAD, novelChapterRowState(index = 3, currentIndex = 2))
    }
}
