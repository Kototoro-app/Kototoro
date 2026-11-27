package org.skepsun.kototoro.list.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingProgressRulesTest {

    @Test
    fun `last page of the last chapter is pinned to a hundred percent`() {
        assertEquals(ReadingProgress.PROGRESS_COMPLETED, readingProgressOf(2, 3, 5, 6))
        assertEquals(ReadingProgress.PROGRESS_COMPLETED, readingProgressOf(0, 1, 0, 1))
    }

    @Test
    fun `reading through never lands just below the completion threshold`() {
        for (chaptersCount in 1..40) {
            val percent = readingProgressOf(
                chapterIndex = chaptersCount - 1,
                chaptersCount = chaptersCount,
                pageIndex = 6,
                pagesCount = 7,
            )

            assertEquals(ReadingProgress.PROGRESS_COMPLETED, percent, "chaptersCount=$chaptersCount")
            assertTrue(isReadingCompleted(percent))
        }
    }

    @Test
    fun `jumping past the last page still counts as read through`() {
        assertEquals(ReadingProgress.PROGRESS_COMPLETED, readingProgressOf(3, 4, 99, 10))
    }

    @Test
    fun `progress inside a chapter is proportional`() {
        // 2nd chapter out of 4, half of it read: 1/4 + 1/4 * 0.5
        assertEquals(0.375f, readingProgressOf(1, 4, 0, 2), 0.0001f)
    }

    @Test
    fun `partial read of the last chapter is not a hundred percent`() {
        val percent = readingProgressOf(2, 3, 0, 4)

        assertTrue(percent < ReadingProgress.PROGRESS_COMPLETED)
        assertTrue(!isReadingCompleted(percent))
    }

    @Test
    fun `the reader reports no progress when it cannot tell where it is`() {
        assertEquals(ReadingProgress.PROGRESS_NONE, readingProgressOf(0, 0, 0, 10))
        assertEquals(ReadingProgress.PROGRESS_NONE, readingProgressOf(0, 10, 0, 0))
        assertEquals(ReadingProgress.PROGRESS_NONE, readingProgressOf(-1, 10, 0, 10))
    }
}
