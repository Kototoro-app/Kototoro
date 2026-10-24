package org.skepsun.kototoro.core.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadPolicyTest {
    @Test
    fun `threads stay inside the slider Android shows`() {
        assertEquals(DownloadPolicy.THREADS_MIN, DownloadPolicy.threads(0))
        assertEquals(DownloadPolicy.THREADS_DEFAULT, DownloadPolicy.threads(DownloadPolicy.THREADS_DEFAULT))
        assertEquals(DownloadPolicy.THREADS_MAX, DownloadPolicy.threads(99))
    }

    @Test
    fun `series cap turns into no limit at the sentinel`() {
        assertEquals(1, DownloadPolicy.activeSeriesLimit(0))
        assertEquals(3, DownloadPolicy.activeSeriesLimit(3))
        assertNull(DownloadPolicy.activeSeriesLimit(DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED))
        assertNull(DownloadPolicy.activeSeriesLimit(DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED + 5))
        assertEquals(DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED, DownloadPolicy.maxActiveSeries(99))
    }

    @Test
    fun `request and retry values clamp to their ranges`() {
        assertEquals(0, DownloadPolicy.requestDelayMs(-1))
        assertEquals(DownloadPolicy.REQUEST_DELAY_MAX_MS, DownloadPolicy.requestDelayMs(58_000))
        assertEquals(DownloadPolicy.RETRY_COUNT_MIN, DownloadPolicy.retryCount(0))
        assertEquals(DownloadPolicy.RETRY_DELAY_MIN_MS, DownloadPolicy.retryDelayMs(10))
        assertEquals(DownloadPolicy.RETRY_DELAY_MAX_MS, DownloadPolicy.retryDelayMs(60_000))
        assertEquals(1, DownloadPolicy.attempts(0))
        assertEquals(3, DownloadPolicy.attempts(3))
    }

    @Test
    fun `a server retry-after wins over the configured delay`() {
        assertEquals(4000L, DownloadPolicy.retryDelayMs(2000, 4000L))
        assertEquals(2000L, DownloadPolicy.retryDelayMs(2000, -1L))
        assertEquals(0L, DownloadPolicy.retryDelayMs(-5, 0L))
        assertEquals(0L, DownloadPolicy.sourceDelayMs(-5))
        assertEquals(1600L, DownloadPolicy.sourceDelayMs(DownloadPolicy.REQUEST_DELAY_DEFAULT_MS))
    }

    @Test
    fun `an absurdly long retry-after is treated as a stop`() {
        assertFalse(DownloadPolicy.isRetryDelayExcessive(DownloadPolicy.RETRY_DELAY_LIMIT_MS))
        assertTrue(DownloadPolicy.isRetryDelayExcessive(DownloadPolicy.RETRY_DELAY_LIMIT_MS + 1))
    }
}
