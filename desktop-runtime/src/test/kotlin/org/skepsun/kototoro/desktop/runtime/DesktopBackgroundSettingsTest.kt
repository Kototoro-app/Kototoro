package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `adult targets cover the surfaces Windows renders and survive the round trip`() {
        val settings = DesktopSourceSettings(
            sort = DesktopSourceSort.LANGUAGE,
            grid = false,
            groupByLanguage = true,
            nsfwTargets = setOf(DesktopNsfwTarget.FEED, DesktopNsfwTarget.UPDATES),
        )
        val stored = settings.toPreferences().mapValues { it.value!! }
        assertEquals(
            SourcePreferenceValue.TextSet(linkedSetOf("FEED", "UPDATES")),
            stored[DesktopSourceSettings.KEY_NSFW_TARGETS],
        )
        assertEquals(settings, DesktopSourceSettings.from(stored))
        // Each target hides only its own surface, like Android's per-target switches on the subscriptions page.
        assertTrue(settings.hidesNsfw(DesktopNsfwTarget.FEED))
        assertTrue(settings.hidesNsfw(DesktopNsfwTarget.UPDATES))
        assertFalse(settings.hidesNsfw(DesktopNsfwTarget.FAVOURITES))
        assertFalse(settings.hidesNsfw(DesktopNsfwTarget.HISTORY))
        assertEquals(
            listOf("SOURCES", "FAVOURITES", "HISTORY", "FEED", "UPDATES", "BOOKMARKS"),
            DesktopNsfwTarget.entries.map { it.name },
        )
    }
}
