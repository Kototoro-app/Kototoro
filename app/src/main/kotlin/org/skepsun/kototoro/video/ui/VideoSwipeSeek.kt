package org.skepsun.kototoro.video.ui

/** Horizontal swipe-to-seek: how much playback time one full screen-width swipe covers. */
internal object VideoSwipeSeek {

    /** 0 keeps the original behaviour: one screen width spans the whole video. */
    const val FULL_LENGTH = 0

    val RANGE_OPTIONS_SEC = listOf(FULL_LENGTH, 600, 300, 180, 90, 60)

    fun offsetMs(deltaX: Float, widthPx: Int, durationMs: Long, rangeSec: Int): Long {
        if (widthPx <= 0 || durationMs <= 0) return 0L
        val rangeMs = if (rangeSec <= FULL_LENGTH) durationMs else minOf(durationMs, rangeSec * 1000L)
        return (deltaX / widthPx * rangeMs).toLong()
    }
}
