package com.kyant.backdrop.internal

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Whether a layer backdrop should re-record its content now.
 *
 * A [com.kyant.backdrop.backdrops.LayerBackdrop] records the whole content it is attached to into a
 * [androidx.compose.ui.graphics.layer.GraphicsLayer] from inside the draw pass, so anything animating behind it
 * (a shared-element transition, a pager, a scrolling list) re-renders the full layer on every frame even though the
 * glass surfaces above only sample it. A non-positive [recordInterval] keeps the historical behaviour: record on every
 * draw.
 *
 * @param elapsedSinceLastRecord time since the previous recording, or `null` when nothing was recorded yet.
 * @param recordInterval minimum spacing between two recordings; `<= 0` disables throttling.
 */
internal fun shouldRecordLayer(
    elapsedSinceLastRecord: Duration?,
    recordInterval: Duration,
): Boolean = recordInterval <= Duration.ZERO ||
    elapsedSinceLastRecord == null ||
    elapsedSinceLastRecord >= recordInterval

/** Tracks when a layer was last recorded; the time source is injectable so the predicate can be tested. */
internal class LayerRecordClock(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var lastRecordMark: TimeMark? = null

    fun shouldRecord(recordInterval: Duration): Boolean =
        shouldRecordLayer(lastRecordMark?.let { it.elapsedNow() }, recordInterval)

    fun markRecorded() {
        lastRecordMark = timeSource.markNow()
    }
}
