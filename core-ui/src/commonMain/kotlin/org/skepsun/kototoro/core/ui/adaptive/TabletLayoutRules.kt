package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared window width classes for tablet layouts; compact keeps the phone layout. */
enum class TabletLayoutClass { COMPACT, MEDIUM, EXPANDED }

/** [tabletLayoutEnabled] is the "tablet UI mode" decision (see FoldableUtils.shouldUseTabletLayout). */
fun tabletLayoutClass(widthDp: Int, tabletLayoutEnabled: Boolean): TabletLayoutClass = when {
    !tabletLayoutEnabled || widthDp < 600 -> TabletLayoutClass.COMPACT
    widthDp < 1000 -> TabletLayoutClass.MEDIUM
    else -> TabletLayoutClass.EXPANDED
}

object TabletLayoutTokens {
    val FilterDrawerWidth = 300.dp
    val PreviewCardWidth = 380.dp
    val PreviewCardMediumWidth = 360.dp
    val OverlayMargin = 12.dp
    val MinVisibleGridWidth = 240.dp
}

/** Preview card width: 380dp on expanded windows, otherwise at most half the window. */
fun tabletPreviewCardWidth(windowWidth: Dp): Dp = if (windowWidth >= 1000.dp) {
    TabletLayoutTokens.PreviewCardWidth
} else {
    minOf(TabletLayoutTokens.PreviewCardMediumWidth, windowWidth / 2)
}

/** Whether the filter drawer and the preview card can both be open and still leave some grid visible. */
fun tabletOverlaysFitTogether(windowWidth: Dp): Boolean =
    windowWidth - TabletLayoutTokens.FilterDrawerWidth - tabletPreviewCardWidth(windowWidth) -
        TabletLayoutTokens.OverlayMargin * 3 >= TabletLayoutTokens.MinVisibleGridWidth
