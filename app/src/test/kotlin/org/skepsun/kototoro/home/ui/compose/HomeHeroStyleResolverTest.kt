package org.skepsun.kototoro.home.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode

class HomeHeroStyleResolverTest {

    private val fixed = HomeHeroPresentation(HomeHeroBackground.COVER_SPLIT, HomeHeroContentLayout.DETAILS)

    @Test
    fun `fixed mode preserves both selected dimensions`() {
        assertEquals(fixed, resolveHomeHeroPresentation(HomeHeroMode.FIXED, fixed, HomeHeroStyleSignals(), 0, 0))
    }

    @Test
    fun `auto prioritizes progress and novel excerpts`() {
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.PLAIN, HomeHeroContentLayout.MINIMAL_PROGRESS),
            resolveHomeHeroPresentation(
                HomeHeroMode.AUTO,
                fixed,
                HomeHeroStyleSignals(isNovel = true, isResume = true),
                0,
                0,
            ),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.TONAL, HomeHeroContentLayout.TEXT_QUOTE),
            resolveHomeHeroPresentation(HomeHeroMode.AUTO, fixed, HomeHeroStyleSignals(isNovel = true), 0, 0),
        )
    }

    @Test
    fun `mixed mode has stable five step rhythm covering every background`() {
        val signals = HomeHeroStyleSignals()
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.TONAL, HomeHeroContentLayout.STANDARD),
            resolveHomeHeroPresentation(HomeHeroMode.MIXED, fixed, signals, 0, 0),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.PLAIN, HomeHeroContentLayout.EDITORIAL),
            resolveHomeHeroPresentation(HomeHeroMode.MIXED, fixed, signals, 1, 0),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.COVER_SPLIT, HomeHeroContentLayout.DETAILS),
            resolveHomeHeroPresentation(HomeHeroMode.MIXED, fixed, signals, 2, 0),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.BLURRED_ARTWORK, HomeHeroContentLayout.STANDARD),
            resolveHomeHeroPresentation(HomeHeroMode.MIXED, fixed, signals, 3, 0),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.IMMERSIVE_ARTWORK, HomeHeroContentLayout.EDITORIAL),
            resolveHomeHeroPresentation(HomeHeroMode.MIXED, fixed, signals, 4, 0),
        )
    }
}
