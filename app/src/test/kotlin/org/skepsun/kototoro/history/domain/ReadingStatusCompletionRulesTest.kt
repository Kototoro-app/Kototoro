package org.skepsun.kototoro.history.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

class ReadingStatusCompletionRulesTest {

    private val untouchedStatuses = listOf(null, ScrobblingStatus.PLANNED, ScrobblingStatus.READING)
    private val deliberateStatuses = listOf(
        ScrobblingStatus.ON_HOLD,
        ScrobblingStatus.DROPPED,
        ScrobblingStatus.RE_READING,
        ScrobblingStatus.COMPLETED,
    )

    @Test
    fun `unfinished status is upgraded when the title is read through`() {
        for (initial in untouchedStatuses) {
            val result = readingStatusAfterCompletion(
                current = initial,
                percent = ReadingProgress.PROGRESS_COMPLETED,
                enabled = true,
            )

            assertEquals(ScrobblingStatus.COMPLETED, result, "initial status $initial")
        }
    }

    @Test
    fun `status the user picked is never overwritten`() {
        for (initial in deliberateStatuses) {
            val result = readingStatusAfterCompletion(
                current = initial,
                percent = ReadingProgress.PROGRESS_COMPLETED,
                enabled = true,
            )

            assertNull(result, "initial status $initial must be kept")
        }
    }

    @Test
    fun `nothing changes when the switch is off`() {
        for (initial in ScrobblingStatus.entries + null) {
            val result = readingStatusAfterCompletion(
                current = initial,
                percent = ReadingProgress.PROGRESS_COMPLETED,
                enabled = false,
            )

            assertNull(result, "initial status $initial must be kept")
        }
    }

    @Test
    fun `unfinished progress leaves any status alone`() {
        for (initial in ScrobblingStatus.entries + null) {
            val result = readingStatusAfterCompletion(
                current = initial,
                percent = 0.5f,
                enabled = true,
            )

            assertNull(result, "initial status $initial must be kept")
        }
    }

    @Test
    fun `progress just below the completion threshold is not a finished read`() {
        assertFalse(ReadingProgress.isCompleted(0.9999f))

        for (initial in untouchedStatuses) {
            val result = readingStatusAfterCompletion(
                current = initial,
                percent = 0.9999f,
                enabled = true,
            )

            assertNull(result, "initial status $initial must be kept")
        }
    }

    @Test
    fun `progress rounded down just below one still counts as read through`() {
        assertTrue(ReadingProgress.isCompleted(0.99999f))

        for (initial in untouchedStatuses) {
            val result = readingStatusAfterCompletion(
                current = initial,
                percent = 0.99999f,
                enabled = true,
            )

            assertEquals(ScrobblingStatus.COMPLETED, result, "initial status $initial")
        }
    }

    @Test
    fun `unknown progress never marks a title completed`() {
        val result = readingStatusAfterCompletion(
            current = ScrobblingStatus.READING,
            percent = ReadingProgress.PROGRESS_NONE,
            enabled = true,
        )

        assertNull(result)
    }
}
