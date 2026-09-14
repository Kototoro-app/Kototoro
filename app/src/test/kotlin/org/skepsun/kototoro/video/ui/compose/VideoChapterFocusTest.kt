package org.skepsun.kototoro.video.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VideoChapterFocusTest {
    @Test
    fun `opening focuses the playing chapter even late in the list`() {
        assertEquals(99, resolveVideoChapterFocusIndex((1L..120L).toList(), null, 100L))
    }

    @Test
    fun `view mode and group changes preserve the browsed chapter`() {
        assertEquals(2, resolveVideoChapterFocusIndex(listOf(10L, 20L, 30L), 30L, 10L))
    }

    @Test
    fun `reordering follows chapter identity instead of its old position`() {
        assertEquals(0, resolveVideoChapterFocusIndex(listOf(30L, 10L, 20L), 30L, 10L))
    }

    @Test
    fun `removed focus falls back to playing chapter then first chapter`() {
        assertEquals(1, resolveVideoChapterFocusIndex(listOf(10L, 20L), 30L, 20L))
        assertEquals(0, resolveVideoChapterFocusIndex(listOf(10L, 20L), 30L, 40L))
    }

    @Test
    fun `empty groups do not request an item focus`() {
        assertNull(resolveVideoChapterFocusIndex(emptyList(), 30L, 20L))
    }
}
