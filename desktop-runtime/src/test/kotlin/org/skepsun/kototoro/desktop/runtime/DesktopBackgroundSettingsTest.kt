package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourcePreferenceValue

class DesktopBackgroundSettingsTest {
    @Test
    fun `settings round trip under Android's keys and defaults`() {
        assertEquals(DesktopSuggestionSettings(enabled = false), DesktopSuggestionSettings.from(emptyMap()))
        assertEquals(DesktopTrackerSettings(enabled = true, frequency = 1f), DesktopTrackerSettings.from(emptyMap()))
        val suggestions = DesktopSuggestionSettings(true, true, setOf("Gore", "Horror"), setOf("Romance"),
            setOf("MIHON_1"), setOf("MIHON_2"))
        val stored = suggestions.toPreferences().mapValues { it.value!! }
        assertEquals(SourcePreferenceValue.Text("Gore, Horror"), stored["suggestions_exclude_tags"])
        assertEquals(SourcePreferenceValue.Toggle(true), stored["suggestions"])
        assertEquals(suggestions, DesktopSuggestionSettings.from(stored))
        val tracker = DesktopTrackerSettings(false, 0.4f)
        assertEquals(SourcePreferenceValue.Text("0.4"), tracker.toPreferences()["tracker_freq"])
        assertEquals(tracker, DesktopTrackerSettings.from(tracker.toPreferences().mapValues { it.value!! }))
    }
}
