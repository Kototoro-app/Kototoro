package org.skepsun.kototoro.history.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HistoryDateGroupTest {
    private val today = 20_000L

    @Test
    fun `days follow Android's history headers`() {
        assertEquals(HistoryDateGroup.JustNow, historyDateGroup(today, today, minutesAgo = 2))
        assertEquals(HistoryDateGroup.Today, historyDateGroup(today, today, minutesAgo = 3))
        assertEquals(HistoryDateGroup.Yesterday, historyDateGroup(today - 1, today, minutesAgo = 2_000))
        assertEquals(HistoryDateGroup.DaysAgo(2), historyDateGroup(today - 2, today, minutesAgo = 3_000))
        assertEquals(HistoryDateGroup.DaysAgo(5), historyDateGroup(today - 5, today, minutesAgo = 8_000))
        assertEquals(HistoryDateGroup.Absolute(today - 6), historyDateGroup(today - 6, today, minutesAgo = 9_000))
    }

    @Test
    fun `a row dated in the future has no header`() {
        assertNull(historyDateGroup(today + 1, today, minutesAgo = -10))
    }
}
