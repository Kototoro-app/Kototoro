package org.skepsun.kototoro.reader.core

import kotlin.math.roundToInt

/**
 * Where an interrupted page turn has to land.
 *
 * A new gesture cancels the running turn animation so it can take the view over, which leaves the
 * paging offset between two slots. A gesture that never becomes a drag (a tap, a double-tap zoom, a
 * long press) used to leave it there: the page stayed frozen half way through the turn, while the
 * reader still reported the nearest page as its settled one. Anything that takes the view over
 * therefore settles the offset onto the nearest slot first.
 *
 * Returns `null` when the offset already sits on a slot boundary, so callers can skip the snap.
 */
fun resolveSettledTurnOffset(
    offset: Float,
    primaryExtent: Float,
    slotCount: Int,
    maxOffset: Float,
): Float? {
    if (primaryExtent <= 0f || slotCount <= 0 || !offset.isFinite()) return null
    val nearestSlot = (offset / primaryExtent).roundToInt().coerceIn(0, slotCount - 1)
    val target = (nearestSlot * primaryExtent).coerceIn(0f, maxOffset.coerceAtLeast(0f))
    return if (target == offset) null else target
}
