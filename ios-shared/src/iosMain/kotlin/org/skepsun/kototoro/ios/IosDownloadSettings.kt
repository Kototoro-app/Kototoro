package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.download.DownloadPolicy

/** Android's download speed and retry limits (core-domain's `DownloadPolicy`) for the iOS settings and queue. */
object IosDownloadSettings {
    val requestDelayMaxMs: Int = DownloadPolicy.REQUEST_DELAY_MAX_MS
    val retryCountDefault: Int = DownloadPolicy.RETRY_COUNT_DEFAULT
    val retryCountMin: Int = DownloadPolicy.RETRY_COUNT_MIN
    val retryCountMax: Int = DownloadPolicy.RETRY_COUNT_MAX
    val retryDelayDefaultMs: Int = DownloadPolicy.RETRY_DELAY_DEFAULT_MS
    val retryDelayMinMs: Int = DownloadPolicy.RETRY_DELAY_MIN_MS
    val retryDelayMaxMs: Int = DownloadPolicy.RETRY_DELAY_MAX_MS

    fun requestDelayMs(value: Int): Int = DownloadPolicy.requestDelayMs(value)

    /** Total attempts for a stored retry count (Android counts attempts, not extra tries). */
    fun attempts(value: Int): Int = DownloadPolicy.attempts(DownloadPolicy.retryCount(value))

    fun retryDelayMs(value: Int): Int = DownloadPolicy.retryDelayMs(value)
}
