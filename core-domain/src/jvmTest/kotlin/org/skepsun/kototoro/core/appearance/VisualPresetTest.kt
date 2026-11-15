package org.skepsun.kototoro.core.appearance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisualPresetTest {

    @Test
    fun `every preset writes only visual keys`() {
        for (preset in VisualPresets.all) {
            assertTrue(
                preset.values.keys.all { it in VisualPreferenceKeys.VISUAL_KEYS },
                "${preset.id} writes ${preset.values.keys - VisualPreferenceKeys.VISUAL_KEYS}",
            )
        }
    }

    @Test
    fun `a preset never touches a non-visual preference`() {
        val current = mapOf(
            VisualPreferenceKeys.THEME to "LIGHT",
            VisualPreferenceKeys.INTERFACE_STYLE to "MATERIAL",
            VisualPreferenceKeys.LANGUAGE to "zh-CN",
            "details_style_manga" to "MANGA",
            "nav_items" to "HOME,BROWSE",
        )
        val application = visualPresetApplication(VisualPresets.byId(VisualPresets.IOS_GROUPED)!!, current)
        assertEquals(
            mapOf(VisualPreferenceKeys.THEME to "SYSTEM", VisualPreferenceKeys.INTERFACE_STYLE to "IOS"),
            application.changes,
        )
        assertTrue(VisualPreferenceKeys.LANGUAGE !in application.changes)
        assertTrue("details_style_manga" !in application.changes)
        assertTrue("nav_items" !in application.changes)
    }

    @Test
    fun `undo restores exactly the values the preset replaced`() {
        val current = mapOf(VisualPreferenceKeys.THEME to "DARK", VisualPreferenceKeys.INTERFACE_STYLE to "MATERIAL")
        val application = visualPresetApplication(VisualPresets.byId(VisualPresets.MATERIAL_COMPACT)!!, current)
        // The interface style already matched the preset, so only the theme is recorded for undo.
        assertEquals(mapOf(VisualPreferenceKeys.THEME to "DARK"), application.undo)
        val restored = current + visualPresetUndoChanges(application)
        assertEquals("DARK", restored[VisualPreferenceKeys.THEME])
        assertEquals("MATERIAL", restored[VisualPreferenceKeys.INTERFACE_STYLE])
    }

    @Test
    fun `a preset the reader is already running reports no changes and no undo`() {
        val current = VisualPresets.byId(VisualPresets.DARK_FOCUS)!!.values
        val application = visualPresetApplication(VisualPresets.byId(VisualPresets.DARK_FOCUS)!!, current)
        assertTrue(application.isEmpty)
        assertTrue(application.undo.isEmpty())
    }

    @Test
    fun `a key with no previous value is written but not undone`() {
        val application = visualPresetApplication(VisualPresets.byId(VisualPresets.DARK_FOCUS)!!, emptyMap())
        assertEquals(mapOf(VisualPreferenceKeys.THEME to "DARK", VisualPreferenceKeys.INTERFACE_STYLE to "MATERIAL"),
            application.changes)
        assertTrue(application.undo.isEmpty())
    }

    @Test
    fun `a preset that names a non-visual key is rejected`() {
        val rogue = VisualPreset(
            id = "ROGUE",
            titleKey = "visualPreset.material",
            summaryKey = "visualPreset.materialSummary",
            values = mapOf("language" to "en"),
        )
        assertThrows(IllegalArgumentException::class.java) { visualPresetApplication(rogue, emptyMap()) }
    }
}
