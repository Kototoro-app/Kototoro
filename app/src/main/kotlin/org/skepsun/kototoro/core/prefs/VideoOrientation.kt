package org.skepsun.kototoro.core.prefs

/** The orientation the video player opens in; the order matches `R.array.video_orientations`. */
enum class VideoOrientation {
    /** Landscape, except portrait on an unfolded foldable held upright. */
    AUTO,
    /** Whatever the rotate button was last left on. */
    LAST_USED,
    LANDSCAPE,
    PORTRAIT,
    REVERSE_LANDSCAPE,
    REVERSE_PORTRAIT,
}
