package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout

class HomeHeroIndicatorPolicyTest {

    @Test
    fun `regular cards anchor the indicator bottom-center`() {
        HomeHeroContentLayout.entries.forEach { layout ->
            assertEquals(
                Alignment.BottomCenter,
                resolveHeroIndicatorPlacement(layout, isSplit = false).alignment,
                "layout=$layout",
            )
        }
    }

    @Test
    fun `split cards anchor the indicator bottom-end to avoid the cover seam`() {
        HomeHeroContentLayout.entries.forEach { layout ->
            assertEquals(
                Alignment.BottomEnd,
                resolveHeroIndicatorPlacement(layout, isSplit = true).alignment,
                "layout=$layout",
            )
        }
    }

    @Test
    fun `bottom-pinned layouts reserve indicator clearance`() {
        assertEquals(
            HERO_INDICATOR_STRIP,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.MINIMAL_PROGRESS, isSplit = false).bottomAvoidance,
        )
        assertEquals(
            HERO_INDICATOR_STRIP,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.TEXT_QUOTE, isSplit = true).bottomAvoidance,
        )
    }

    @Test
    fun `editorial only reserves clearance when the indicator shares its end corner`() {
        assertEquals(
            0.dp,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.EDITORIAL, isSplit = false).bottomAvoidance,
        )
        assertEquals(
            HERO_INDICATOR_STRIP,
            resolveHeroIndicatorPlacement(HomeHeroContentLayout.EDITORIAL, isSplit = true).bottomAvoidance,
        )
    }

    @Test
    fun `centered row layouts never reserve clearance`() {
        assertEquals(0.dp, resolveHeroIndicatorPlacement(HomeHeroContentLayout.STANDARD, isSplit = false).bottomAvoidance)
        assertEquals(0.dp, resolveHeroIndicatorPlacement(HomeHeroContentLayout.DETAILS, isSplit = false).bottomAvoidance)
    }
}
