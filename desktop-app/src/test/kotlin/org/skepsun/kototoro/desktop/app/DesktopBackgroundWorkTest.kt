package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings
import org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateSettings

class DesktopBackgroundWorkTest {
    private val hour = 3_600_000L

    private fun schedules(now: Long, trackerLast: Long, suggestionsLast: Long,
        tracker: DesktopTrackerSettings = DesktopTrackerSettings(),
        suggestions: DesktopSuggestionSettings = DesktopSuggestionSettings(enabled = true)) =
        desktopBackgroundSchedules(trackerLast, suggestionsLast, tracker, suggestions)

    @Test
    fun `jobs run on Android's intervals while enabled`() {
        val on = DesktopSuggestionSettings(enabled = true)
        val tracker = DesktopTrackerSettings()
        assertEquals(setOf(DesktopBackgroundTask.TRACKER, DesktopBackgroundTask.SUGGESTIONS),
            dueTasks(100 * hour, schedules(100 * hour, 0, 0, tracker, on)), "never run")
        assertEquals(setOf(DesktopBackgroundTask.SUGGESTIONS),
            dueTasks(17 * hour, schedules(17 * hour, 0, 10 * hour, tracker, on)),
            "a full check every 18 h at the default frequency; suggestions every 6 h")
        assertEquals(emptySet<DesktopBackgroundTask>(),
            dueTasks(17 * hour, schedules(17 * hour, 0, 12 * hour, tracker, on)))
        assertEquals(setOf(DesktopBackgroundTask.TRACKER),
            dueTasks(9 * hour, schedules(9 * hour, 0, 9 * hour, DesktopTrackerSettings(frequency = 2f), on)),
            "high frequency: 9 h")
        assertEquals(emptySet<DesktopBackgroundTask>(), dueTasks(1000 * hour,
            schedules(1000 * hour, 0, 0, DesktopTrackerSettings(frequency = -1f), DesktopSuggestionSettings())),
            "manual tracker and disabled suggestions never run on their own")
        assertEquals(emptySet<DesktopBackgroundTask>(),
            dueTasks(1000 * hour, schedules(1000 * hour, 0, 1000 * hour, DesktopTrackerSettings(enabled = false), on)))
    }

    @Test
    fun `the loop sleeps until the nearest job is due`() {
        val schedules = schedules(now = 0, trackerLast = 0, suggestionsLast = 4 * hour)
        // Suggestions are due in 2 h, the tracker in 18 h: wake in 2 h, clamped to the settings-refresh ceiling.
        assertEquals(MAXIMUM_WAKE_MS, nextWakeDelay(0, schedules))
        // 5 h later the nearest job is still 5 h away, beyond the ceiling that keeps settings changes responsive.
        assertEquals(MAXIMUM_WAKE_MS, nextWakeDelay(5 * hour, schedules))
        // Just before a job is due the loop waits the exact remainder instead of polling.
        assertEquals(MINIMUM_WAKE_MS, nextWakeDelay(17 * hour + 59 * 60_000L, schedules), "one minute before the tracker")
        assertEquals(MINIMUM_WAKE_MS, nextWakeDelay(40 * hour, schedules), "a job due now never spins")
        assertNull(nextWakeDelay(0, schedules.filter { false }), "nothing scheduled: nothing to wait for")
        assertNull(nextWakeDelay(0, schedules(0, 0, 0, tracker = DesktopTrackerSettings(frequency = -1f),
            suggestions = DesktopSuggestionSettings())))
    }

    @Test
    fun `the update check follows its own setting`() {
        val recent = 1_000L
        val due = appUpdateSchedule(0L, DesktopUpdateSettings(autoCheck = true))
        assertEquals(DesktopBackgroundTask.APP_UPDATE, due.task)
        assertEquals(UPDATE_CHECK_INTERVAL_HOURS * hour, due.intervalMillis)
        assertEquals(setOf(DesktopBackgroundTask.APP_UPDATE), dueTasks(UPDATE_CHECK_INTERVAL_HOURS * hour, listOf(due)))
        assertEquals(emptySet<DesktopBackgroundTask>(), dueTasks(recent, listOf(due)), "not before the interval")
        assertNull(appUpdateSchedule(0L, DesktopUpdateSettings(autoCheck = false)).intervalMillis,
            "automatic checks disabled: the job is not scheduled")
    }
}
