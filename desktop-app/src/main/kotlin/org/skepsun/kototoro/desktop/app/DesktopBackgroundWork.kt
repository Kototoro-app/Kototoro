package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings
import org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings
import org.skepsun.kototoro.suggestions.domain.SUGGESTIONS_INTERVAL_HOURS
import org.skepsun.kototoro.tracker.domain.trackerCheckIntervalHours

internal enum class DesktopBackgroundTask { TRACKER, SUGGESTIONS }

private const val HOUR_MS = 3_600_000L

/**
 * Android schedules the tracker and the suggestions as periodic WorkManager jobs; Windows runs the same intervals
 * while the app is open. Windows checks every track in one run, so a full check happens every 18 h / frequency.
 */
internal fun dueBackgroundTasks(
    now: Long,
    lastTrackerRun: Long,
    lastSuggestionsRun: Long,
    tracker: DesktopTrackerSettings,
    suggestions: DesktopSuggestionSettings,
): Set<DesktopBackgroundTask> = buildSet {
    if (tracker.enabled) {
        trackerCheckIntervalHours(trackCount = 1, batchSize = Int.MAX_VALUE, frequency = tracker.frequency)
            ?.let { hours -> if (now - lastTrackerRun >= hours * HOUR_MS) add(DesktopBackgroundTask.TRACKER) }
    }
    if (suggestions.enabled && now - lastSuggestionsRun >= SUGGESTIONS_INTERVAL_HOURS * HOUR_MS) {
        add(DesktopBackgroundTask.SUGGESTIONS)
    }
}

/** Android's tracker frequency choices (TrackerSettingsRoute): manual, less, default and more frequently. */
internal val DesktopTrackerFrequencies = listOf(-1f to "手动", 0.4f to "低频", 1f to "默认", 2f to "高频")
