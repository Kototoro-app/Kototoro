package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout

class HomeHeroIndicatorPolicyTest {

    @Test
    fun `all layouts anchor pagination away from the central content`() {
        HomeHeroContentLayout.entries.forEach { layout ->
            assertEquals(
                Alignment.BottomEnd,
                resolveHeroIndicatorPlacement(layout).alignment,
                "layout=$layout",
            )
        }
    }

    @Test
    fun `bottom-pinned layouts reserve indicator clearance`() {
        assertEquals(
            HERO_INDICATOR_STRIP,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.MINIMAL_PROGRESS).bottomAvoidance,
        )
        assertEquals(
            HERO_INDICATOR_STRIP,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.TEXT_QUOTE).bottomAvoidance,
        )
    }

    @Test
    fun `editorial reserves clearance for its end-aligned poster`() {
        assertEquals(
            HERO_INDICATOR_STRIP,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.EDITORIAL).bottomAvoidance,
        )
    }

    @Test
    fun `centered row layouts never reserve clearance`() {
        assertEquals(0.dp, resolveHeroIndicatorPlacement(HomeHeroContentLayout.STANDARD).bottomAvoidance)
        assertEquals(0.dp, resolveHeroIndicatorPlacement(HomeHeroContentLayout.DETAILS).bottomAvoidance)
    }
}
