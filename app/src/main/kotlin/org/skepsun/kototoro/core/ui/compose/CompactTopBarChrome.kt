package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shared application layout tokens. Reader/player page content has its own geometry. */
object AppLayoutTokens {
    val screenHorizontalPadding = 12.dp
    val sectionHorizontalPadding = 12.dp
    val sectionVerticalSpacing = 16.dp
    val compactItemHorizontalPadding = 12.dp
}

val CompactTopBarPillShape = RoundedCornerShape(28.dp)
val CompactTopBarPillHeight = 40.dp
val CompactTopBarCompactButtonSize = 36.dp
/** Shared horizontal edge for application chrome and page content. */
val CompactTopBarHorizontalPadding = AppLayoutTokens.screenHorizontalPadding
val CompactTopBarItemSpacing = 8.dp
val CompactTopBarIconSize = 18.dp
