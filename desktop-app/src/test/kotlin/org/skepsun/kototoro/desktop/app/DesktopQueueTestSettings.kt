package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.desktop.runtime.DesktopDownloadSettings

/**
 * The settings the older queue tests were written against: one page at a time, one series at a time, no retries and no
 * spacing, so their page counts and call counters stay deterministic. Tests that exercise the parallel path pass their
 * own settings instead.
 */
internal fun serialQueueSettings(): () -> DesktopDownloadSettings = {
    DesktopDownloadSettings(threads = 1, maxActiveSeries = 1, requestDelayMs = 0, retryCount = 1, retryDelayMs = 500)
}
