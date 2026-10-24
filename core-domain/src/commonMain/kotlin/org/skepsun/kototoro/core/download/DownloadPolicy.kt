package org.skepsun.kototoro.core.download

/**
 * How downloads are allowed to behave, shared by the Android worker and the Windows queue.
 *
 * The defaults follow Kotatsu-Redo (four pages at a time, two retries two seconds apart, 1600 ms spacing only for
 * sources that ask for it). The user-facing values live in each platform's preference store under the same keys, and
 * are passed through these clamps so a hand-edited or out-of-date document cannot ask for impossible values.
 */
object DownloadPolicy {
    /** Pages fetched at the same time inside one chapter. */
    const val THREADS_DEFAULT = 4
    const val THREADS_MIN = 1
    const val THREADS_MAX = 10

    /** Series downloaded at the same time; [MAX_ACTIVE_SERIES_UNLIMITED] means "no cap". */
    const val MAX_ACTIVE_SERIES_DEFAULT = 4
    const val MAX_ACTIVE_SERIES_UNLIMITED = 11

    /** Spacing between two requests to a source that asked for slowdown. */
    const val REQUEST_DELAY_DEFAULT_MS = 1600
    const val REQUEST_DELAY_MAX_MS = 5000

    /** Retries for one page, and the wait before each. */
    const val RETRY_COUNT_DEFAULT = 2
    const val RETRY_COUNT_MIN = 1
    const val RETRY_COUNT_MAX = 10
    const val RETRY_DELAY_DEFAULT_MS = 2000
    const val RETRY_DELAY_MIN_MS = 500
    const val RETRY_DELAY_MAX_MS = 10_000

    /** A `Retry-After` longer than two hours is treated as "stop and ask", not as a wait. */
    const val RETRY_DELAY_LIMIT_MS = 7_200_000L

    /** How many chapters of the same series may run at once; unbounded when the cap is switched off. */
    const val SERIES_CHAPTERS_DEFAULT = 1

    fun threads(value: Int): Int = value.coerceIn(THREADS_MIN, THREADS_MAX)

    fun maxActiveSeries(value: Int): Int = value.coerceIn(1, MAX_ACTIVE_SERIES_UNLIMITED)

    fun requestDelayMs(value: Int): Int = value.coerceIn(0, REQUEST_DELAY_MAX_MS)

    fun retryCount(value: Int): Int = value.coerceIn(RETRY_COUNT_MIN, RETRY_COUNT_MAX)

    fun retryDelayMs(value: Int): Int = value.coerceIn(RETRY_DELAY_MIN_MS, RETRY_DELAY_MAX_MS)

    /** Spacing between requests to a slowdown-enabled source; 0 turns the spacing off. */
    fun sourceDelayMs(settingDelayMs: Int): Long = settingDelayMs.toLong().coerceAtLeast(0L)

    /** A server-provided Retry-After wins; otherwise the fixed user delay, as in Kotatsu-Redo. */
    fun retryDelayMs(settingDelayMs: Int, serverDelayMs: Long): Long =
        if (serverDelayMs > 0L) serverDelayMs else settingDelayMs.toLong().coerceAtLeast(0L)

    /** `null` when the series cap is disabled. */
    fun activeSeriesLimit(setting: Int, unlimited: Int = MAX_ACTIVE_SERIES_UNLIMITED): Int? =
        if (setting >= unlimited) null else setting.coerceAtLeast(1)

    /** Attempts one page gets: the user's count, never less than one try. */
    fun attempts(retryCount: Int): Int = retryCount.coerceAtLeast(1)

    /** Whether a `Retry-After` is long enough that waiting is pointless. */
    fun isRetryDelayExcessive(delayMs: Long): Boolean = delayMs > RETRY_DELAY_LIMIT_MS
}
