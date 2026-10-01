package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.migration.ui.config.FamilySources
import org.skepsun.kototoro.migration.ui.config.SourcePreset
import org.skepsun.kototoro.migration.ui.config.withPreset
import org.skepsun.kototoro.migration.ui.config.SourcePickerEntry
import org.skepsun.kototoro.migration.ui.config.SourcePickerFilters
import org.skepsun.kototoro.migration.ui.config.SourceSelectionFilter
import org.skepsun.kototoro.migration.ui.config.filterPickerEntries
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class SourcePickerFiltersTest {
    private val entries = listOf(
        entry("B", "Beta manga", "en", SourceType.MIHON),
        entry("A", "Alpha manga", "en", SourceType.NATIVE),
        entry("C", "中文漫画", "zh", SourceType.MIHON),
        entry("D", "Gamma", "en", SourceType.MIHON),
    )

    @Test
    fun combinedFiltersPreserveSourcePriorityAndDoNotChangeSelection() {
        val selected = setOf("B", "C")
        val result = filterPickerEntries(entries, SourcePickerFilters(
            query = " manga ", language = "en", type = SourceType.MIHON,
            pinnedOnly = true, selection = SourceSelectionFilter.SELECTED,
        ), selected, setOf("B", "D"))
        assertEquals(listOf("B"), result.map { it.source.name })
        assertEquals(setOf("B", "C"), selected)
    }

    @Test
    fun selectedAndUnselectedFiltersPartitionTheAvailableSources() {
        val selected = setOf("B", "C")
        assertEquals(listOf("B", "C"), filterPickerEntries(entries,
            SourcePickerFilters(selection = SourceSelectionFilter.SELECTED), selected, emptySet())
            .map { it.source.name })
        assertEquals(listOf("A", "D"), filterPickerEntries(entries,
            SourcePickerFilters(selection = SourceSelectionFilter.UNSELECTED), selected, emptySet())
            .map { it.source.name })
    }

    @Test
    fun sortingIsExplicitAndNonLatinInitialsHaveAnHonestFallback() {
        assertEquals(listOf("B", "A", "C", "D"),
            filterPickerEntries(entries, SourcePickerFilters(), emptySet(), emptySet()).map { it.source.name })
        assertEquals(listOf("A", "B", "D", "C"),
            filterPickerEntries(entries, SourcePickerFilters(alphabetical = true), emptySet(), emptySet())
                .map { it.source.name })
        assertEquals("#", entries[2].initial)
    }

    @Test
    fun queryMatchesDisplayNamesAndUnknownQueriesProduceNoRows() {
        assertEquals(listOf("C"), filterPickerEntries(entries, SourcePickerFilters(query = "中文"),
            emptySet(), emptySet()).map { it.source.name })
        assertEquals(emptyList<SourcePickerEntry>(), filterPickerEntries(entries,
            SourcePickerFilters(query = "Missing source"), emptySet(), emptySet()))
    }

    private fun entry(name: String, title: String, locale: String, type: SourceType) = SourcePickerEntry(
        object : ContentSource {
            override val name = name
            override val locale = locale
            override val contentType = ContentType.MANGA
        }, title, locale, type,
    )

    @Test
    fun scopedBulkActionsKeepHiddenSelectionsAndExistingTickOrder() {
        val family = FamilySources(ContentTypeFamily.MANGA, entries.map { it.source }, setOf("B"), listOf("C", "B"))
        assertEquals(listOf("C", "B", "A", "D"), family.withPreset(SourcePreset.ALL, listOf("A", "B", "D")).selected)
        assertEquals(listOf("C"), family.withPreset(SourcePreset.NONE, listOf("B", "D")).selected)
        assertEquals(listOf("C", "B"), family.withPreset(SourcePreset.ALL, listOf("Unknown", "B", "B")).selected)
        assertEquals(listOf("B", "A", "C", "D"), family.withPreset(SourcePreset.ALL).selected)
        assertEquals(emptyList<String>(), family.withPreset(SourcePreset.NONE).selected)
    }
}
