package org.skepsun.kototoro.core.ui.home

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.feed.feedUpdatedCardPosition
import org.skepsun.kototoro.core.ui.feed.feedUpdatedCardWidthForPosition
import org.skepsun.kototoro.core.ui.feed.feedUpdatedFocusWidth

class HomeRulesTest {
    @Test
    fun `hero lists resume first then capped sections without the resume work`() {
        val history = (1L..8L).map { HomeHeroCandidate("h$it", it) }
        val updates = listOf(HomeHeroCandidate("u1", 1L, 3), HomeHeroCandidate("u9", 9L, 2))
        val entries = buildHomeHeroEntries("h1", 1L, 40, { 1L }, history, updates,
            recommendations = listOf(HomeHeroCandidate("r", 20L)), limits = HomeHeroLimits(total = 6, history = 3))
        assertEquals(listOf(HomeHeroKind.RESUME, HomeHeroKind.HISTORY, HomeHeroKind.HISTORY, HomeHeroKind.HISTORY,
            HomeHeroKind.UPDATE, HomeHeroKind.RECOMMENDATION), entries.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L, 4L, 9L, 20L), entries.map { it.groupKey })
        assertEquals(40, entries.first().progressPercent)
        assertEquals(2, entries[4].newChapters)
        assertTrue(buildHomeHeroEntries<String>(null, null, null, { 0L }, emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `grid columns, counts and hero widths follow Android's tablet rules`() {
        assertEquals(5, homeBalancedGridColumns(10, 9))
        assertEquals(4, homeBalancedGridColumns(8, 4))
        assertEquals(1, homeBalancedGridColumns(0, 4))
        assertEquals("999", homeCountLabel(999))
        assertEquals("1k", homeCountLabel(1_500))
        assertEquals("12k+", homeCountLabel(12_345))
        assertEquals(312.dp, homeHeroCardWidth(1000.dp, 16.dp, 6.dp, TabletLayoutClass.COMPACT))
        assertEquals(2, (1280.dp / homeHeroCardWidth(1280.dp, 0.dp, 6.dp, TabletLayoutClass.EXPANDED)).toInt())
        assertEquals(3, (1500.dp / homeHeroCardWidth(1500.dp, 0.dp, 6.dp, TabletLayoutClass.EXPANDED)).toInt())
    }

    @Test
    fun `carousel cards shrink with distance from the focus`() {
        val focus = feedUpdatedFocusWidth(1000.dp, 1f)
        assertEquals(380.dp, focus)
        assertEquals(focus, feedUpdatedCardWidthForPosition(focus, 0f))
        assertTrue(feedUpdatedCardWidthForPosition(focus, 1f) < focus)
        assertTrue(feedUpdatedCardWidthForPosition(focus, 2f) < feedUpdatedCardWidthForPosition(focus, 1f))
        assertEquals(0f, feedUpdatedCardPosition(null, 0, 16f, 380f))
        assertEquals(1f, feedUpdatedCardPosition(396, 0, 16f, 380f))
        assertEquals(3f, feedUpdatedCardPosition(10_000, 0, 16f, 380f), "positions clamp at three cards")
    }
}
