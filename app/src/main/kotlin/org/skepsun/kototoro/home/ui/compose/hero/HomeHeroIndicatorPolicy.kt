package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout

/**
 * Placement policy for the in-card hero pager indicator. The indicator lives at
 * the bottom of the hero card, so content layouts that pin information to the
 * bottom edge must reserve clearance for it ("avoidance").
 *
 * Pagination stays in the end corner, separate from the reading progress.
 * Editorial posters and bottom-pinned text reserve the same clearance.
 */
internal data class HeroIndicatorPlacement(
    val alignment: Alignment,
    val bottomAvoidance: Dp,
)

internal fun resolveHeroIndicatorPlacement(
    contentLayout: HomeHeroContentLayout,
): HeroIndicatorPlacement {
    val bottomAvoidance = when (contentLayout) {
        HomeHeroContentLayout.MINIMAL_PROGRESS,
        HomeHeroContentLayout.TEXT_QUOTE,
        HomeHeroContentLayout.EDITORIAL -> HERO_INDICATOR_STRIP
        else -> 0.dp
    }
    return HeroIndicatorPlacement(Alignment.BottomEnd, bottomAvoidance)
}

internal val HERO_INDICATOR_STRIP: Dp = 20.dp
