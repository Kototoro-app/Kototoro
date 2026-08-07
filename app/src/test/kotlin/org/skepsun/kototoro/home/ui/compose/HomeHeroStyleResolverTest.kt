package org.skepsun.kototoro.home.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.parsers.model.ContentType

class HomeHeroStyleResolverTest {

    private val fixed = HomeHeroPresentation(HomeHeroBackground.COVER_SPLIT, HomeHeroContentLayout.DETAILS)

    @Test
    fun `fixed mode preserves both selected dimensions`() {
        assertEquals(
            fixed,
            resolveHomeHeroPresentation(
                HomeHeroMode.FIXED,
                fixed,
                HomeHeroStyleSignals(ContentType.MANGA),
                0,
                0,
            ),
        )
    }

    @Test
    fun `auto uses content type background while preserving contextual layout`() {
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.TONAL, HomeHeroContentLayout.MINIMAL_PROGRESS),
            resolveHomeHeroPresentation(
                HomeHeroMode.AUTO,
                fixed,
                HomeHeroStyleSignals(ContentType.NOVEL, isResume = true),
                0,
                0,
            ),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.TONAL, HomeHeroContentLayout.TEXT_QUOTE),
            resolveHomeHeroPresentation(HomeHeroMode.AUTO, fixed, HomeHeroStyleSignals(ContentType.NOVEL), 0, 0),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.BLURRED_ARTWORK, HomeHeroContentLayout.STANDARD),
            resolveHomeHeroPresentation(HomeHeroMode.AUTO, fixed, HomeHeroStyleSignals(ContentType.MANGA), 0, 0),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.BLURRED_ARTWORK, HomeHeroContentLayout.MINIMAL_PROGRESS),
            resolveHomeHeroPresentation(
                HomeHeroMode.AUTO,
                fixed,
                HomeHeroStyleSignals(ContentType.MANGA, isResume = true),
                0,
                0,
            ),
        )
        assertEquals(
            HomeHeroPresentation(HomeHeroBackground.IMMERSIVE_ARTWORK, HomeHeroContentLayout.STANDARD),
            resolveHomeHeroPresentation(HomeHeroMode.AUTO, fixed, HomeHeroStyleSignals(ContentType.VIDEO), 0, 0),
        )
    }

    @Test
    fun `auto applies the same background policy to adult content types`() {
        assertEquals(
            HomeHeroBackground.BLURRED_ARTWORK,
            resolveHomeHeroPresentation(
                HomeHeroMode.AUTO,
                fixed,
                HomeHeroStyleSignals(ContentType.HENTAI_MANGA),
                0,
                0,
            ).background,
        )
        assertEquals(
            HomeHeroBackground.TONAL,
            resolveHomeHeroPresentation(
                HomeHeroMode.AUTO,
                fixed,
                HomeHeroStyleSignals(ContentType.HENTAI_NOVEL),
                0,
                0,
            ).background,
        )
        assertEquals(
            HomeHeroBackground.IMMERSIVE_ARTWORK,
            resolveHomeHeroPresentation(
                HomeHeroMode.AUTO,
                fixed,
                HomeHeroStyleSignals(ContentType.HENTAI_VIDEO),
                0,
                0,
            ).background,
        )
    }

    @Test
    fun `mixed mode has stable five step rhythm covering every background`() {
        val signals = HomeHeroStyleSignals(ContentType.MANGA)
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
