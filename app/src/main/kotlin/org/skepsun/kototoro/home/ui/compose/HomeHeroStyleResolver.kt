package org.skepsun.kototoro.home.ui.compose

import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode

internal data class HomeHeroPresentation(
    val background: HomeHeroBackground,
    val contentLayout: HomeHeroContentLayout,
)

internal data class HomeHeroStyleSignals(
    val isNovel: Boolean = false,
    val isResume: Boolean = false,
    val hasDistinctLargeCover: Boolean = false,
    val isRecommendation: Boolean = false,
)

internal fun resolveHomeHeroPresentation(
    mode: HomeHeroMode,
    fixedPresentation: HomeHeroPresentation,
    signals: HomeHeroStyleSignals,
    page: Int,
    mixedSeed: Int,
): HomeHeroPresentation = when (mode) {
    HomeHeroMode.FIXED -> fixedPresentation
    HomeHeroMode.AUTO -> when {
        signals.isResume -> HomeHeroPresentation(
            HomeHeroBackground.PLAIN,
            HomeHeroContentLayout.MINIMAL_PROGRESS,
        )
        signals.isNovel -> HomeHeroPresentation(
            HomeHeroBackground.TONAL,
            HomeHeroContentLayout.TEXT_QUOTE,
        )
        signals.hasDistinctLargeCover -> HomeHeroPresentation(
            HomeHeroBackground.IMMERSIVE_ARTWORK,
            HomeHeroContentLayout.STANDARD,
        )
        signals.isRecommendation -> HomeHeroPresentation(
            HomeHeroBackground.PLAIN,
            HomeHeroContentLayout.EDITORIAL,
        )
        else -> HomeHeroPresentation(
            HomeHeroBackground.TONAL,
            HomeHeroContentLayout.STANDARD,
        )
    }
    HomeHeroMode.MIXED -> when (Math.floorMod(page + Math.floorMod(mixedSeed, 5), 5)) {
        0 -> HomeHeroPresentation(HomeHeroBackground.TONAL, HomeHeroContentLayout.STANDARD)
        1 -> if (signals.isNovel) {
            HomeHeroPresentation(HomeHeroBackground.PLAIN, HomeHeroContentLayout.TEXT_QUOTE)
        } else {
            HomeHeroPresentation(HomeHeroBackground.PLAIN, HomeHeroContentLayout.EDITORIAL)
        }
        2 -> HomeHeroPresentation(
            HomeHeroBackground.COVER_SPLIT,
            if (signals.isResume) HomeHeroContentLayout.MINIMAL_PROGRESS else HomeHeroContentLayout.DETAILS,
        )
        3 -> HomeHeroPresentation(HomeHeroBackground.BLURRED_ARTWORK, HomeHeroContentLayout.STANDARD)
        else -> HomeHeroPresentation(HomeHeroBackground.IMMERSIVE_ARTWORK, HomeHeroContentLayout.EDITORIAL)
    }
}
