package org.skepsun.kototoro.home.ui.compose.hero

import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.parsers.model.ContentType

internal data class HomeHeroPresentation(
    val background: HomeHeroBackground,
    val contentLayout: HomeHeroContentLayout,
)

internal data class HomeHeroStyleSignals(
    val contentType: ContentType,
    val isResume: Boolean = false,
    val hasDistinctLargeCover: Boolean = false,
    val isRecommendation: Boolean = false,
) {
    val isNovel: Boolean
        get() = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL
}

internal fun resolveHomeHeroPresentation(
    mode: HomeHeroMode,
    fixedPresentation: HomeHeroPresentation,
    signals: HomeHeroStyleSignals,
    page: Int,
    mixedSeed: Int,
): HomeHeroPresentation = when (mode) {
    HomeHeroMode.FIXED -> fixedPresentation
    HomeHeroMode.AUTO -> HomeHeroPresentation(
        background = signals.resolveAutoBackground(),
        contentLayout = when {
            signals.isResume -> HomeHeroContentLayout.MINIMAL_PROGRESS
            signals.isNovel -> HomeHeroContentLayout.TEXT_QUOTE
            signals.isRecommendation -> HomeHeroContentLayout.EDITORIAL
            else -> HomeHeroContentLayout.STANDARD
        },
    )
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

private fun HomeHeroStyleSignals.resolveAutoBackground(): HomeHeroBackground = when (contentType) {
    ContentType.NOVEL,
    ContentType.HENTAI_NOVEL -> HomeHeroBackground.TONAL
    ContentType.VIDEO,
    ContentType.HENTAI_VIDEO -> HomeHeroBackground.IMMERSIVE_ARTWORK
    ContentType.MANGA,
    ContentType.MANHWA,
    ContentType.MANHUA,
    ContentType.HENTAI_MANGA,
    ContentType.COMICS,
    ContentType.ONE_SHOT,
    ContentType.DOUJINSHI,
    ContentType.IMAGE_SET,
    ContentType.ARTIST_CG,
    ContentType.GAME_CG -> HomeHeroBackground.BLURRED_ARTWORK
    ContentType.OTHER -> when {
        hasDistinctLargeCover -> HomeHeroBackground.IMMERSIVE_ARTWORK
        isRecommendation -> HomeHeroBackground.PLAIN
        else -> HomeHeroBackground.TONAL
    }
}
