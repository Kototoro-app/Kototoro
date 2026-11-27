package org.skepsun.kototoro.explore.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Keeps a horizontal rail's leftover drag and fling to itself. Explore's Sources / Discover pager
 * would otherwise pick up what a rail cannot use at its first or last item and turn the page in the
 * middle of the same gesture.
 */
internal fun Modifier.keepHorizontalScrollInside(): Modifier = nestedScroll(KeepHorizontalScrollInside)

private object KeepHorizontalScrollInside : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(x = available.x, y = 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = available.x, y = 0f)
}
