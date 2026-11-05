package com.kyant.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Kototoro patch -- process-wide rendering quality for Backdrop surfaces. See backdrop/UPSTREAM.md.
 *
 * Surfaces with a render effect record their backdrop layer at [inputScale] of their size (the GPU saving described on
 * `DrawBackdropNode`) and scale it back up; 1 renders every effect at full resolution. The presets follow Haze's
 * `HazePerformanceMode` (Performance / Balanced / Quality). It is snapshot state that nodes read while resolving their
 * effects, so a change re-resolves live surfaces without recreating them.
 */
object BackdropQuality {

    const val PERFORMANCE = 0.5f

    /** sqrt(0.625): 62.5% of the full-resolution pixels, Haze's default `Balanced` profile. */
    const val BALANCED = 0.7905694f

    const val QUALITY = 1f

    var inputScale: Float by mutableFloatStateOf(BALANCED)
}
