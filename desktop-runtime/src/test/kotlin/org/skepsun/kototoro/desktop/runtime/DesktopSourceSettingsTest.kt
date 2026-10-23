package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.core.source.SourcePreferenceValue

class DesktopSourceSettingsTest {

    @Test
    fun `the stored selection round-trips through the preference values`() {
        val settings = DesktopSourceSettings(
            sort = DesktopSourceSort.LANGUAGE,
            grid = false,
            groupByLanguage = true,
            nsfwTargets = setOf(DesktopNsfwTarget.FAVOURITES, DesktopNsfwTarget.UPDATES),
            tagBlacklist = setOf("genre.ecchi", "raw:drama"),
        )
        val restored = DesktopSourceSettings.from(settings.toPreferences().filterValues { it != null }
            .mapValues { it.value!! })
        assertEquals(settings, restored)
    }

    @Test
    fun `unknown and blank entries fall back instead of breaking the page`() {
        val restored = DesktopSourceSettings.from(mapOf(
            DesktopSourceSettings.KEY_SORT to SourcePreferenceValue.Text("SIDEWAYS"),
            DesktopSourceSettings.KEY_NSFW_TARGETS to SourcePreferenceValue.TextSet(linkedSetOf("SOURCES", "GONE")),
            DesktopSourceSettings.KEY_TAG_BLACKLIST to SourcePreferenceValue.TextSet(linkedSetOf("", " ", "genre.ecchi")),
        ))
        assertEquals(DesktopSourceSort.DEFAULT, restored.sort)
        assertEquals(setOf(DesktopNsfwTarget.SOURCES), restored.nsfwTargets)
        // A blank stored value is dropped, so the page never renders an empty chip.
        assertEquals(setOf("genre.ecchi"), restored.tagBlacklist)
    }

    @Test
    fun `the blacklist rules hide taxonomy ids, aliases and raw patterns`() {
        assertTrue(DesktopSourceSettings().tagBlacklistRules() === TagBlacklist.Empty)
        val rules = DesktopSourceSettings(tagBlacklist = setOf("setting.other-world")).tagBlacklistRules()
        assertTrue(rules.containsTagTitle("Isekai"))
        assertFalse(rules.containsTagTitle("Drama"))
    }

    @Test
    fun `Android's key name is reused so a shared backup carries the selection`() {
        assertEquals("global_tag_blacklist", DesktopSourceSettings.KEY_TAG_BLACKLIST)
    }
}
