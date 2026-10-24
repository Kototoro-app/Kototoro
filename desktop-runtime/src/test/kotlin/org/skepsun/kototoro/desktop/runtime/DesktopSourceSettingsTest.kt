package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRef

class DesktopSourceSettingsTest {

    @Test
    fun `the stored selection round-trips through the preference values`() {
        val settings = DesktopSourceSettings(
            sort = DesktopSourceSort.LANGUAGE,
            grid = false,
            groupByLanguage = true,
            nsfwTargets = setOf(DesktopNsfwTarget.FAVOURITES, DesktopNsfwTarget.UPDATES),
            tagBlacklist = setOf("genre.ecchi", "raw:drama"),
            tagsWarnings = false,
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
        assertEquals("tags_warnings", DesktopSourceSettings.KEY_TAGS_WARNINGS)
    }

    @Test
    fun `tag warnings default on and follow the shared warn list rather than a desktop copy`() {
        val settings = DesktopSourceSettings()
        assertTrue(settings.tagsWarnings)
        assertTrue(settings.isTagWarning("Rape"))
        assertTrue(settings.isTagWarning("Гуро"))
        assertFalse(settings.isTagWarning("Drama"))
        // Turning the setting off keeps the list but stops the tint, exactly like Android's `isTagsWarningsEnabled`.
        val off = settings.copy(tagsWarnings = false)
        assertFalse(off.isTagWarning("Rape"))
        assertFalse(
            DesktopSourceSettings.from(
                mapOf(DesktopSourceSettings.KEY_TAGS_WARNINGS to SourcePreferenceValue.Toggle(false)),
            ).tagsWarnings,
        )
    }

    private fun adult(): SourceContent {
        val source = SourceRef("MIHON_1", "en", "MANGA")
        return SourceContent(id = 5, title = "18+", altTitles = emptySet(), url = "/a", publicUrl = "/a",
            rating = 0f, contentRating = "ADULT", coverUrl = null, tags = emptySet(), state = null,
            authors = emptySet(), source = source, chapters = null)
    }

    private fun safe(): SourceContent {
        val source = SourceRef("MIHON_1", "en", "MANGA")
        return SourceContent(id = 6, title = "All ages", altTitles = emptySet(), url = "/b", publicUrl = "/b",
            rating = 0f, contentRating = "SAFE", coverUrl = null, tags = emptySet(), state = null,
            authors = emptySet(), source = source, chapters = null)
    }

    @Test
    fun `the incognito choice reuses Android's key and asks by default`() {
        assertEquals("incognito_nsfw", DesktopSourceSettings.KEY_INCOGNITO_NSFW)
        val settings = DesktopSourceSettings()
        assertEquals(DesktopTriState.ASK, settings.incognitoForNsfw)
        assertTrue(settings.asksForIncognito(adult()))
        assertFalse(settings.requiresIncognito(adult()))
        // Android stores the enum name, so a shared backup reads back as the same option.
        assertEquals(DesktopTriState.ASK,
            DesktopSourceSettings.from(mapOf(
                DesktopSourceSettings.KEY_INCOGNITO_NSFW to SourcePreferenceValue.Text(DesktopTriState.ASK.name),
            )).incognitoForNsfw)
    }

    @Test
    fun `the adult rule only touches adult works`() {
        val always = DesktopSourceSettings(incognitoForNsfw = DesktopTriState.ENABLED)
        assertTrue(always.requiresIncognito(adult()))
        assertFalse(always.asksForIncognito(adult()))
        assertFalse(always.requiresIncognito(safe()))
        val never = DesktopSourceSettings(incognitoForNsfw = DesktopTriState.DISABLED)
        assertFalse(never.requiresIncognito(adult()))
        assertFalse(never.asksForIncognito(adult()))
    }

    @Test
    fun `an unknown stored option falls back to asking`() {
        assertEquals(DesktopTriState.ASK,
            DesktopSourceSettings.from(mapOf(
                DesktopSourceSettings.KEY_INCOGNITO_NSFW to SourcePreferenceValue.Text("SOMETIMES"),
            )).incognitoForNsfw)
        assertEquals(DesktopTriState.ASK,
            DesktopSourceSettings.from(mapOf(
                DesktopSourceSettings.KEY_INCOGNITO_NSFW to SourcePreferenceValue.Toggle(true),
            )).incognitoForNsfw)
    }
}
