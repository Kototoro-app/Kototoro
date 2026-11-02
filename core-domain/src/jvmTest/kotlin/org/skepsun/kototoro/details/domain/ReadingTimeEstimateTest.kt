package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadingTimeEstimateTest {

    @Test
    fun mangaUsesTwentyPagesPerChapterAndTheRemainingShare() {
        assertEquals(20L * 10 * 30, ReadingTimeEstimate.remainingSeconds("MANGA", 30, null, 0))
        assertEquals(20L * 6 * 30 / 2, ReadingTimeEstimate.remainingSeconds("HENTAI_MANGA", 30, 0.5f, 6))
    }

    @Test
    fun novelsVideosAndShortRemaindersGetNoEstimate() {
        assertNull(ReadingTimeEstimate.remainingSeconds("NOVEL", 300, 0.1f, 5))
        assertNull(ReadingTimeEstimate.remainingSeconds("VIDEO", 12, null, 5))
        assertNull(ReadingTimeEstimate.remainingSeconds("MANGA", 1, 0.9f, 10))
    }
}
