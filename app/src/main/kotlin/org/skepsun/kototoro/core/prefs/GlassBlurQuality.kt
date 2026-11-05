package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep
import com.kyant.backdrop.BackdropQuality

/** Resolution of the backdrop that glass and edge blurs are computed from, after Haze's `HazePerformanceMode`. */
@Keep
enum class GlassBlurQuality(val inputScale: Float) {
    PERFORMANCE(BackdropQuality.PERFORMANCE),
    BALANCED(BackdropQuality.BALANCED),
    QUALITY(BackdropQuality.QUALITY),
}
