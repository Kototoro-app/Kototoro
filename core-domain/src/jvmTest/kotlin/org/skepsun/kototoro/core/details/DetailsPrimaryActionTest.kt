package org.skepsun.kototoro.core.details

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailsPrimaryActionTest {

    private val chapters = listOf(
        DetailsChapterRef(1L, "第 1 话"),
        DetailsChapterRef(2L, "第 2 话"),
        DetailsChapterRef(3L, "第 3 话"),
    )

    @Test
    fun `a work with no chapters has nothing to start`() {
        val action = detailsPrimaryAction(emptyList(), currentChapterId = null)
        assertEquals(DetailsPrimaryActionKind.NONE, action.kind)
        assertFalse(action.isAvailable)
        assertNull(action.chapterId)
        assertEquals(0, action.chapterCount)
    }

    @Test
    fun `a work never opened starts at its first chapter`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = null)
        assertEquals(DetailsPrimaryActionKind.START, action.kind)
        assertEquals(1L, action.chapterId)
        assertEquals("第 1 话", action.chapterTitle)
        assertEquals(3, action.chapterCount)
    }

    @Test
    fun `the history chapter becomes the continue target with its own title`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = 2L)
        assertEquals(DetailsPrimaryActionKind.CONTINUE, action.kind)
        assertEquals(2L, action.chapterId)
        assertEquals("第 2 话", action.chapterTitle)
    }

    @Test
    fun `a chapter from another branch does not hijack the action`() {
        // The history points at a chapter this branch does not have, so the action falls back to starting.
        val action = detailsPrimaryAction(chapters, currentChapterId = 99L, readThroughChapterId = 1L)
        assertEquals(DetailsPrimaryActionKind.START, action.kind)
        assertEquals(2L, action.chapterId)
    }

    @Test
    fun `the furthest read chapter moves the start to the one after it`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = null, readThroughChapterId = 2L)
        assertEquals(DetailsPrimaryActionKind.START, action.kind)
        assertEquals(3L, action.chapterId)
    }

    @Test
    fun `a finished work starts over from the first chapter`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = null, readThroughChapterId = 3L)
        assertEquals(DetailsPrimaryActionKind.START, action.kind)
        assertEquals(1L, action.chapterId)
        assertTrue(action.isAvailable)
    }
}
