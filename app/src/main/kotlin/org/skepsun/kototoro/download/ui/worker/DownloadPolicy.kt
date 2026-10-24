package org.skepsun.kototoro.download.ui.worker

import org.skepsun.kototoro.core.download.DownloadPolicy as SharedDownloadPolicy
import java.io.IOException

/**
 * The Android side of the shared download rules: the numbers live in [SharedDownloadPolicy] (the Windows queue reads
 * the same ones), and only what needs the JVM's exception types stays here.
 */
internal object DownloadPolicy {
    const val HLS_SEGMENT_CONCURRENCY = 3
    const val MAX_WORK_RETRIES = 2

    /** Spacing between requests to a slowdown-enabled source; 0 turns the spacing off. */
    fun sourceDelayMs(settingDelayMs: Int): Long = SharedDownloadPolicy.sourceDelayMs(settingDelayMs)

    /** A server-provided Retry-After wins; otherwise the fixed user delay, as in Kotatsu-Redo. */
    fun retryDelayMs(settingDelayMs: Int, serverDelayMs: Long): Long =
        SharedDownloadPolicy.retryDelayMs(settingDelayMs, serverDelayMs)

    /** `null` when the series cap is disabled. */
    fun activeSeriesLimit(setting: Int, unlimited: Int): Int? =
        SharedDownloadPolicy.activeSeriesLimit(setting, unlimited)

    fun shouldRetry(error: Throwable): Boolean = error is IOException
}
