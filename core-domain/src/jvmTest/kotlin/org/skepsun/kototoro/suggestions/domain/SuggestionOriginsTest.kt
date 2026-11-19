package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionOriginsTest {

    @Test
    fun `type and tags decide the origin before the source language`() {
        assertEquals("comic/kr", SuggestionOrigins.groupOf("MANHWA", emptyList(), "zh"))
        assertEquals("comic/cn", SuggestionOrigins.groupOf("MANGA", listOf("Manhua"), "ja"))
        assertEquals("comic/kr", SuggestionOrigins.groupOf("MANGA", listOf("韩漫"), "zh-CN"))
        assertEquals("comic/jp", SuggestionOrigins.groupOf("MANGA", listOf("Manga"), "zh"))
        assertEquals("comic/cn", SuggestionOrigins.groupOf("MANGA", emptyList(), "zh_CN"))
        assertEquals("comic/other", SuggestionOrigins.groupOf("COMICS", emptyList(), "ja"))
        assertEquals("novel", SuggestionOrigins.groupOf("NOVEL", emptyList(), "zh"))
        assertEquals("video", SuggestionOrigins.groupOf("HENTAI_VIDEO", emptyList(), null))
    }

    private data class Item(val id: Long, val group: String)

    private fun rank(items: List<Item>, limits: SuggestionLimits) = rankSuggestions(
        items.mapIndexed { rank, item -> SuggestionCandidate(item, SuggestionDirection.POPULAR, null, rank) },
        SuggestionProfile.of(emptyList(), 0L), { it.id }, { "s${it.id % 7}" }, { listOf("t${it.id}") }, { emptyList() },
        now = 0L, limits = limits, groupOf = { it.group },
    ).map { it.item }

    @Test
    fun `one group cannot take the list while others have candidates and fills only what is left`() {
        val items = (1L..60L).map { Item(it, "comic/cn") } + (61L..70L).map { Item(it, "comic/jp") } +
            (71L..75L).map { Item(it, "comic/kr") }
        val limits = SuggestionLimits(maxResults = 20, maxResultsPerSource = 20)
        val picked = rank(items, limits)
        assertEquals(20, picked.size)
        val uncapped = rank(items, limits.copy(maxGroupShare = 1f)).count { it.group == "comic/cn" }
        val capped = picked.count { it.group == "comic/cn" }
        assertTrue(capped <= 10 && uncapped > 10 && capped < uncapped, "share 40% of 20 is 8, but $capped of $uncapped remain")
        // Nobody else left: the capped group fills the rest instead of leaving the list short.
        assertEquals(20, rank(items.filter { it.group == "comic/cn" }, limits).size)
    }
}
