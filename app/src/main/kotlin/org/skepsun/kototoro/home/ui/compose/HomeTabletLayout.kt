package org.skepsun.kototoro.home.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import kotlin.math.ceil

/** Home content stops growing here on wide windows and centres instead of stretching sections. */
internal val HomeContentMaxWidth = 1200.dp

private val HomeHeroCompactCardWidth = 312.dp
private val HomeHeroMaxCardWidth = 640.dp
private val HomeHeroThreeCardViewport = 1400.dp

/**
 * Columns for a grid of [itemCount] items with at most [maxColumns] per row, spread so rows are as
 * even as possible: 10 items on 9 columns become 5 + 5 rather than 9 + one stretched straggler.
 */
internal fun balancedGridColumns(itemCount: Int, maxColumns: Int): Int {
    val limit = maxColumns.coerceAtLeast(1)
    if (itemCount <= 0) return 1
    val rows = ceil(itemCount / limit.toDouble()).toInt()
    return ceil(itemCount / rows.toDouble()).toInt().coerceIn(1, limit)
}

/**
 * Hero card width for a [viewport]. Phones keep the fixed card; medium windows show one and a half
 * cards so the next one peeks in; expanded windows fill the row with two (three from 1400dp) cards,
 * each capped so a lone entry does not become a banner.
 */
internal fun homeHeroCardWidth(
    viewport: Dp,
    edgePadding: Dp,
    spacing: Dp,
    layoutClass: TabletLayoutClass,
): Dp = when (layoutClass) {
    TabletLayoutClass.COMPACT -> minOf(HomeHeroCompactCardWidth, viewport * 0.78f)
        .coerceAtMost((viewport - edgePadding * 2).coerceAtLeast(0.dp))
    TabletLayoutClass.MEDIUM -> ((viewport - edgePadding - spacing) / 1.5f)
        .coerceIn(HomeHeroCompactCardWidth, HomeHeroMaxCardWidth)
    TabletLayoutClass.EXPANDED -> {
        val cards = if (viewport >= HomeHeroThreeCardViewport) 3 else 2
        ((viewport - edgePadding * 2 - spacing * (cards - 1)) / cards)
            .coerceIn(HomeHeroCompactCardWidth, HomeHeroMaxCardWidth)
    }
}
