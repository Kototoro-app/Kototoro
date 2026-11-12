package org.skepsun.kototoro.history.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterReadStateTest {

    private val chapters = (1..20).map { ChapterOrder(id = 100L + it, number = it.toFloat(), volume = 0) }

    @Test
    fun `first read records the opened chapter`() {
        assertEquals(110L, furthestReadChapterId(0L, 110L, chapters))
    }

    @Test
    fun `reading further moves the furthest chapter`() {
        assertEquals(120L, furthestReadChapterId(110L, 120L, chapters))
    }

    @Test
    fun `reopening an earlier chapter keeps the furthest one`() {
        assertEquals(120L, furthestReadChapterId(120L, 110L, chapters))
    }

    @Test
    fun `a furthest chapter missing from the list falls back to the opened one`() {
        assertEquals(105L, furthestReadChapterId(999L, 105L, chapters))
        assertEquals(105L, furthestReadChapterId(999L, 105L, null))
    }

    @Test
    fun `an opened chapter missing from the list keeps the furthest one`() {
        assertEquals(120L, furthestReadChapterId(120L, 999L, chapters))
    }

    @Test
    fun `volume decides before chapter number when both are known`() {
        val volumes = listOf(ChapterOrder(1, 5f, 1), ChapterOrder(2, 1f, 2))
        assertEquals(2L, furthestReadChapterId(1L, 2L, volumes))
        assertEquals(2L, furthestReadChapterId(2L, 1L, volumes))
    }

    @Test
    fun `read through is the later of current and furthest`() {
        val tenth = chapters[9]
        val twentieth = chapters[19]
        assertEquals(twentieth, chapterReadThrough(tenth, twentieth))
        assertEquals(twentieth, chapterReadThrough(twentieth, tenth))
        assertEquals(tenth, chapterReadThrough(tenth, null))
        assertEquals(twentieth, chapterReadThrough(null, twentieth))
        assertNull(chapterReadThrough(null, null))
    }

    @Test
    fun `chapters after the read through chapter stay unread`() {
        val through = chapterReadThrough(chapters[9], chapters[19])!!
        val unread = chapters.filter { chapterIsAfter(it.number, it.volume, through.number, through.volume) }
        assertTrue(unread.isEmpty(), "episodes 11-20 must stay read after revisiting episode 10")
    }
}
