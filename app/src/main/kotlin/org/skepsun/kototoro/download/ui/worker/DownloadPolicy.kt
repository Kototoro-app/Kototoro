package org.skepsun.kototoro.download.ui.worker

import java.io.IOException

/**
 * Download behaviour. Defaults follow Kotatsu-Redo (4 pages in parallel, 2 retries 2s apart,
 * 1600ms spacing only for sources that ask for slowdown); the user-facing values come from
 * [org.skepsun.kototoro.core.prefs.AppSettings] and are passed in by the worker.
 */
internal object DownloadPolicy {
    const val HLS_SEGMENT_CONCURRENCY = 3
    const val MAX_WORK_RETRIES = 2

    /** Spacing between requests to a slowdown-enabled source; 0 turns the spacing off. */
    fun sourceDelayMs(settingDelayMs: Int): Long = settingDelayMs.toLong().coerceAtLeast(0L)

    /** A server-provided Retry-After wins; otherwise the fixed user delay, as in Kotatsu-Redo. */
    fun retryDelayMs(settingDelayMs: Int, serverDelayMs: Long): Long {
        if (serverDelayMs > 0L) {
            return serverDelayMs
        }
        return settingDelayMs.toLong().coerceAtLeast(0L)
    }

    /** `null` when the series cap is disabled. */
    fun activeSeriesLimit(setting: Int, unlimited: Int): Int? = if (setting >= unlimited) null else setting.coerceAtLeast(1)

    fun shouldRetry(error: Throwable): Boolean = error is IOException
}
