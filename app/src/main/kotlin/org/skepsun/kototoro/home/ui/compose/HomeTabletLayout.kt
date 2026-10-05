package org.skepsun.kototoro.home.ui.compose

import androidx.compose.ui.unit.Dp
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.home.homeBalancedGridColumns

// The home layout rules are shared with the Windows host (core-ui home/HomeUi.kt).

/** Home content stops growing here on wide windows and centres instead of stretching sections. */
internal val HomeContentMaxWidth: Dp = org.skepsun.kototoro.core.ui.home.HomeContentMaxWidth

/** See [homeBalancedGridColumns]. */
internal fun balancedGridColumns(itemCount: Int, maxColumns: Int): Int = homeBalancedGridColumns(itemCount, maxColumns)

/** See [org.skepsun.kototoro.core.ui.home.homeHeroCardWidth]. */
internal fun homeHeroCardWidth(
    viewport: Dp,
    edgePadding: Dp,
    spacing: Dp,
    layoutClass: TabletLayoutClass,
): Dp = org.skepsun.kototoro.core.ui.home.homeHeroCardWidth(viewport, edgePadding, spacing, layoutClass)
