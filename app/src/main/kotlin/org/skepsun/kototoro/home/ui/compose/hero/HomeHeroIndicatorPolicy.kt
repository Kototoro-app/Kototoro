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
 * - Bottom-center for regular cards (the standard carousel look).
 * - Bottom-end for cover-split cards, where the left pane is a full-height
 *   cover image and a centered indicator would straddle the cover/info seam.
 */
internal data class HeroIndicatorPlacement(
    val alignment: Alignment,
    val bottomAvoidance: Dp,
)

internal fun resolveHeroIndicatorPlacement(
    contentLayout: HomeHeroContentLayout,
    isSplit: Boolean,
): HeroIndicatorPlacement {
    val alignment = if (isSplit) Alignment.BottomEnd else Alignment.BottomCenter
    // Layouts that pin text or the progress bar to the bottom edge lift their
    // content by the indicator strip height. EDITORIAL anchors its poster to
    // the bottom-END, which leaves the bottom-center clear on regular cards;
    // on split cards the indicator moves to the same end corner, so the poster
    // (also end-aligned) must lift too.
    val bottomAvoidance = when {
        contentLayout == HomeHeroContentLayout.MINIMAL_PROGRESS -> HERO_INDICATOR_STRIP
        contentLayout == HomeHeroContentLayout.TEXT_QUOTE -> HERO_INDICATOR_STRIP
        contentLayout == HomeHeroContentLayout.EDITORIAL && isSplit -> HERO_INDICATOR_STRIP
        else -> 0.dp
    }
    return HeroIndicatorPlacement(alignment, bottomAvoidance)
}

internal val HERO_INDICATOR_STRIP: Dp = 20.dp
