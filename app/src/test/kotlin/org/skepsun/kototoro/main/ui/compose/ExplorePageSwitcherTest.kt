package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ExplorePage

class ExplorePageSwitcherTest {

    @Test
    fun `capsule selection follows the pager page after a swipe`() {
        assertEquals(ExplorePage.SOURCES.name, exploreSwitcherSelectedId(currentPage = 0))
        assertEquals(ExplorePage.DISCOVERY.name, exploreSwitcherSelectedId(currentPage = 1))
    }

    @Test
    fun `tapping the capsule targets the matching pager page`() {
        assertEquals(1, explorePagerTargetFor(ExplorePage.DISCOVERY.name, targetPage = 0))
        assertEquals(0, explorePagerTargetFor(ExplorePage.SOURCES.name, targetPage = 1))
        assertNull(explorePagerTargetFor(ExplorePage.DISCOVERY.name, targetPage = 1))
        assertNull(explorePagerTargetFor("UNKNOWN", targetPage = 0))
    }

    @Test
    fun `tap then settle round-trips through the capsule id`() {
        ExplorePage.entries.forEach { page ->
            val target = explorePagerTargetFor(page.name, targetPage = -1)!!
            assertEquals(page.name, exploreSwitcherSelectedId(target))
        }
    }

    @Test
    fun `settled page is saved only when it differs from the setting`() {
        assertEquals(ExplorePage.DISCOVERY, explorePageToPersist(settledPage = 1, saved = ExplorePage.SOURCES))
        assertNull(explorePageToPersist(settledPage = 1, saved = ExplorePage.DISCOVERY))
        assertEquals(ExplorePage.SOURCES, explorePageAt(7))
    }
}
