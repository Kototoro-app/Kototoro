package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings
import org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateSettings
import org.skepsun.kototoro.suggestions.domain.SUGGESTIONS_INTERVAL_HOURS
import org.skepsun.kototoro.tracker.domain.trackerCheckIntervalHours

internal enum class DesktopBackgroundTask { TRACKER, SUGGESTIONS, APP_UPDATE }

internal const val HOUR_MS = 3_600_000L

/**
 * Android checks for application updates when it starts; a Windows window can stay open for days, so the update check
 * repeats while it runs. Six hours keeps a security-relevant release discoverable without polling GitHub often.
 */
internal const val UPDATE_CHECK_INTERVAL_HOURS = 6L

/** Never spin: a job that is due right after a run still waits this long. */
internal const val MINIMUM_WAKE_MS = 60_000L

/**
 * A settings change has to take effect without a restart, so the loop re-reads its schedule at least this often even
 * when nothing is due — Android gets the same effect by observing preferences.
 */
internal const val MAXIMUM_WAKE_MS = 30 * 60_000L

/** One periodic job: what it is, how often it runs (null when its setting turns it off or makes it manual), and when. */
internal data class DesktopBackgroundSchedule(
    val task: DesktopBackgroundTask,
    val intervalMillis: Long?,
    val lastRun: Long,
)

/**
 * Android schedules the tracker and the suggestions as periodic WorkManager jobs; Windows runs the same intervals
 * while the app is open. Windows checks every track in one run, so a full check happens every 18 h / frequency.
 */
internal fun desktopBackgroundSchedules(
    lastTrackerRun: Long,
    lastSuggestionsRun: Long,
    tracker: DesktopTrackerSettings,
    suggestions: DesktopSuggestionSettings,
): List<DesktopBackgroundSchedule> = listOf(
    DesktopBackgroundSchedule(
        task = DesktopBackgroundTask.TRACKER,
        intervalMillis = if (!tracker.enabled) null else {
            trackerCheckIntervalHours(trackCount = 1, batchSize = Int.MAX_VALUE, frequency = tracker.frequency)
                ?.let { (it * HOUR_MS).toLong() }
        },
        lastRun = lastTrackerRun,
    ),
    DesktopBackgroundSchedule(
        task = DesktopBackgroundTask.SUGGESTIONS,
        intervalMillis = (SUGGESTIONS_INTERVAL_HOURS * HOUR_MS).takeIf { suggestions.enabled },
        lastRun = lastSuggestionsRun,
    ),
)

/** The application-update check: the only job whose schedule depends on a preference that is not a work interval. */
internal fun appUpdateSchedule(lastRun: Long, settings: DesktopUpdateSettings) = DesktopBackgroundSchedule(
    task = DesktopBackgroundTask.APP_UPDATE,
    intervalMillis = (UPDATE_CHECK_INTERVAL_HOURS * HOUR_MS).takeIf { settings.autoCheck },
    lastRun = lastRun,
)

/** The jobs whose interval has already elapsed at [now]. */
internal fun dueTasks(now: Long, schedules: List<DesktopBackgroundSchedule>): Set<DesktopBackgroundTask> =
    schedules.filter { it.intervalMillis != null && it.lastRun + it.intervalMillis <= now }
        .mapTo(linkedSetOf()) { it.task }

/**
 * How long the loop may sleep before re-checking: the shortest remaining time to a due job, clamped so it neither
 * spins nor ignores a settings change for long. Null when nothing is scheduled at all.
 */
internal fun nextWakeDelay(now: Long, schedules: List<DesktopBackgroundSchedule>): Long? {
    val next = schedules.mapNotNull { it.intervalMillis?.let { interval -> it.lastRun + interval - now } }.minOrNull()
        ?: return null
    return next.coerceIn(MINIMUM_WAKE_MS, MAXIMUM_WAKE_MS)
}

/** Android's tracker frequency choices (TrackerSettingsRoute): manual, less, default and more frequently. */
internal fun desktopTrackerFrequencies(): List<Pair<Float, String>> = listOf(
    -1f to desktopText("settings.frequency.manual"),
    0.4f to desktopText("settings.frequency.low"),
    1f to desktopText("settings.frequency.default"),
    2f to desktopText("settings.frequency.high"),
)
