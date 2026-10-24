package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
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
        // Android has no key of its own for the manual sequence (it writes each source row's `sort_key`).
        assertEquals("sources_order", DesktopSourceSettings.KEY_ORDER)
    }

    @Test
    fun `the hand picked sequence keeps its order across the preference values`() {
        val settings = DesktopSourceSettings(
            sort = DesktopSourceSort.CUSTOM,
            order = listOf("MIHON_9", "MIHON_2", "MIHON_7"),
        )
        val restored = DesktopSourceSettings.from(settings.toPreferences().filterValues { it != null }
            .mapValues { it.value!! })
        assertEquals(settings, restored)
        // A text set is unordered by contract; the page still reads back what the editor stored.
        assertEquals(listOf("MIHON_9", "MIHON_2", "MIHON_7"), restored.order)
        assertEquals(listOf("MIHON_9", "MIHON_2", "MIHON_7"),
            restored.sorted(listOf(listing("MIHON_2"), listing("MIHON_7"), listing("MIHON_9")))
                .map { it.source.name })
    }

    @Test
    fun `the custom order ranks the stored names first and keeps the rest in install order`() {
        val installed = listOf(listing("MIHON_1"), listing("MIHON_2"), listing("MIHON_3"))
        // A sequence naming a source that is gone still ranks the sources it does name; the rest follow untouched.
        val settings = DesktopSourceSettings(sort = DesktopSourceSort.CUSTOM,
            order = listOf("MIHON_3", "MIHON_GONE", "MIHON_1"))
        assertEquals(listOf("MIHON_3", "MIHON_1", "MIHON_2"), settings.sorted(installed).map { it.source.name })
        // Picking the order before saving anything falls back to the installation order.
        assertEquals(listOf("MIHON_1", "MIHON_2", "MIHON_3"),
            settings.copy(order = emptyList()).sorted(installed).map { it.source.name })
        // The other orders ignore the sequence entirely.
        assertEquals(listOf("MIHON_1", "MIHON_2", "MIHON_3"),
            settings.copy(sort = DesktopSourceSort.DEFAULT).sorted(installed).map { it.source.name })
    }

    private fun listing(name: String) =
        SourceListing(SourceRef(name, "zh", "MANGA"), name, SourceEcosystem.MIHON, supportsLatest = false)

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
