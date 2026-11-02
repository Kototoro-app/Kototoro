package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.source.SourcePreferenceValue

class DesktopLibraryDisplayTest {
    @Test
    fun `the earlier two-value key keeps its look and the new key round-trips every offered mode`() {
        // The old GRID was drawn with the title over the poster: Android's compact grid.
        assertEquals(ListMode.COMPACT_GRID, DesktopLibraryDisplay.from(mapOf(
            DesktopLibraryDisplay.KEY_LEGACY_LIST_MODE to SourcePreferenceValue.Text("GRID"))).listMode)
        assertEquals(ListMode.LIST, DesktopLibraryDisplay.from(mapOf(
            DesktopLibraryDisplay.KEY_LEGACY_LIST_MODE to SourcePreferenceValue.Text("LIST"))).listMode)
        assertEquals(DesktopLibraryDisplay(), DesktopLibraryDisplay.from(emptyMap()))
        for (mode in DesktopLibraryDisplay.MODES) {
            val display = DesktopLibraryDisplay(mode, 135)
            @Suppress("UNCHECKED_CAST")
            assertEquals(display, DesktopLibraryDisplay.from(display.toPreferences() as Map<String, SourcePreferenceValue>))
        }
        // Movie grids are not offered here; an out-of-range size is clamped to the slider's range.
        val stored = mapOf(DesktopLibraryDisplay.KEY_LIST_MODE to SourcePreferenceValue.Text("MOVIE_GRID"),
            DesktopLibraryDisplay.KEY_GRID_SIZE to SourcePreferenceValue.Integer(400))
        assertEquals(DesktopLibraryDisplay(ListMode.COMPACT_GRID, 150), DesktopLibraryDisplay.from(stored))
    }
}
