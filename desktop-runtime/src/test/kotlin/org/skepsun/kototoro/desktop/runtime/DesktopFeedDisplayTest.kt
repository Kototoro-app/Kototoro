package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

class DesktopFeedDisplayTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `an empty store keeps drawing the updated block`() {
        assertTrue(DesktopFeedDisplay.from(emptyMap()).showUpdated, "Android's feed_header defaults to on")
    }

    @Test
    fun `hiding and showing the updated block round trips through feed_header`() {
        FileSourcePreferenceStore(directory.resolve("prefs")).use { store ->
            val preferences = store.open("desktop_library")
            assertTrue(preferences.edit(SourcePreferenceEdit(changes = DesktopFeedDisplay(showUpdated = false).toPreferences())))
            assertEquals(DesktopFeedDisplay(showUpdated = false), DesktopFeedDisplay.from(preferences.snapshot()))
            assertEquals(
                SourcePreferenceValue.Toggle(false),
                preferences.snapshot()[DesktopFeedDisplay.KEY_SHOW_UPDATED],
            )
            assertTrue(preferences.edit(SourcePreferenceEdit(changes = DesktopFeedDisplay(showUpdated = true).toPreferences())))
            assertTrue(DesktopFeedDisplay.from(store.open("desktop_library").snapshot()).showUpdated)
        }
    }

    @Test
    fun `a value of the wrong type falls back to the default instead of failing the page`() {
        val stored = mapOf(DesktopFeedDisplay.KEY_SHOW_UPDATED to SourcePreferenceValue.Text("false"))
        assertTrue(DesktopFeedDisplay.from(stored).showUpdated)
        assertFalse(DesktopFeedDisplay(showUpdated = false).toPreferences()[DesktopFeedDisplay.KEY_SHOW_UPDATED] == null)
    }
}
