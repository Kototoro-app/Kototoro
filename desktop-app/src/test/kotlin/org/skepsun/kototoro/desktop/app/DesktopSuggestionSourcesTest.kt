package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourceRef

class DesktopSuggestionSourcesTest {
    private fun option(name: String, contentType: String, ecosystem: SourceEcosystem, title: String = name) =
        SuggestionSourceOption(name, title, contentType, ecosystem)

    private val options = listOf(
        option("a", "MANGA", SourceEcosystem.MIHON, "Alpha"),
        option("b", "NOVEL", SourceEcosystem.KOTOTORO, "Beta"),
        option("c", "VIDEO", SourceEcosystem.ANIYOMI, "Gamma"),
        option("d", "MANGA", SourceEcosystem.KOTOTORO, "Delta"),
    )

    @Test
    fun `an empty query and empty facets keep every source in the host's own order`() {
        assertEquals(options, filterSuggestionSourceOptions(options, "  ", emptySet(), emptySet()))
    }

    @Test
    fun `query matches the display name and the source id case-insensitively`() {
        assertEquals(listOf(options[2]), filterSuggestionSourceOptions(options, "gam", emptySet(), emptySet()))
        assertEquals(listOf(options[1]), filterSuggestionSourceOptions(options, "B", emptySet(), emptySet()))
    }

    @Test
    fun `facets intersect each other and the query`() {
        assertEquals(listOf(options[3]),
            filterSuggestionSourceOptions(options, "", setOf("MANGA"), setOf(SourceEcosystem.KOTOTORO)))
        assertEquals(listOf(options[3]),
            filterSuggestionSourceOptions(options, "del", setOf("MANGA"), setOf(SourceEcosystem.KOTOTORO)))
        assertEquals(emptyList<SuggestionSourceOption>(),
            filterSuggestionSourceOptions(options, "alpha", setOf("NOVEL"), emptySet()))
    }

    @Test
    fun `content type chips follow Android's enum order and ecosystems keep the source order`() {
        assertEquals(listOf("MANGA", "NOVEL", "VIDEO"), availableContentTypes(options))
        assertEquals(listOf(SourceEcosystem.MIHON, SourceEcosystem.KOTOTORO, SourceEcosystem.ANIYOMI),
            availableEcosystems(options))
    }

    @Test
    fun `the summary shows three names then a count, and the empty hint while nothing is selected`() {
        val empty = "no sources"
        assertEquals(empty, suggestionSourceSummary(emptySet(), options, empty))
        assertEquals("Beta", suggestionSourceSummary(setOf("b"), options, empty))
        assertEquals("Alpha, Beta, Gamma", suggestionSourceSummary(setOf("c", "a", "b"), options, empty))
        assertEquals("Alpha, Beta, Gamma +1", suggestionSourceSummary(options.map { it.id }.toSet(), options, empty))
    }

    @Test
    fun `options carry the protocol identity so the stored set matches Android's source ids`() {
        val listing = SourceListing(SourceRef("fixture", "zh", "MANGA"), "Fixture", SourceEcosystem.MIHON, true)
        assertEquals(listOf(SuggestionSourceOption("fixture", "Fixture", "MANGA", SourceEcosystem.MIHON)),
            suggestionSourceOptions(listOf(listing)))
    }
}
