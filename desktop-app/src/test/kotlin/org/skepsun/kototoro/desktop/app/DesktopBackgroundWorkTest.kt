package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings
import org.skepsun.kototoro.desktop.runtime.DesktopTrackerSettings

class DesktopBackgroundWorkTest {
    private val hour = 3_600_000L

    @Test
    fun `jobs run on Android's intervals while enabled`() {
        val on = DesktopSuggestionSettings(enabled = true)
        val tracker = DesktopTrackerSettings()
        assertEquals(setOf(DesktopBackgroundTask.TRACKER, DesktopBackgroundTask.SUGGESTIONS),
            dueBackgroundTasks(now = 100 * hour, lastTrackerRun = 0, lastSuggestionsRun = 0, tracker, on), "never run")
        assertEquals(setOf(DesktopBackgroundTask.SUGGESTIONS),
            dueBackgroundTasks(now = 17 * hour, lastTrackerRun = 0, lastSuggestionsRun = 10 * hour, tracker, on),
            "a full check every 18 h at the default frequency; suggestions every 6 h")
        assertEquals(emptySet<DesktopBackgroundTask>(),
            dueBackgroundTasks(now = 17 * hour, lastTrackerRun = 0, lastSuggestionsRun = 12 * hour, tracker, on))
        assertEquals(setOf(DesktopBackgroundTask.TRACKER),
            dueBackgroundTasks(9 * hour, 0, 9 * hour, DesktopTrackerSettings(frequency = 2f), on), "high frequency: 9 h")
        assertEquals(emptySet<DesktopBackgroundTask>(),
            dueBackgroundTasks(1000 * hour, 0, 0, DesktopTrackerSettings(frequency = -1f), DesktopSuggestionSettings()),
            "manual tracker and disabled suggestions never run on their own")
        assertEquals(emptySet<DesktopBackgroundTask>(),
            dueBackgroundTasks(1000 * hour, 0, 1000 * hour, DesktopTrackerSettings(enabled = false), on))
    }
}
