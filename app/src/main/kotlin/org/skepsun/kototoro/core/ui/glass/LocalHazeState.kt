package org.skepsun.kototoro.core.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
import org.skepsun.kototoro.core.prefs.AppSettings

val LocalHazeState = staticCompositionLocalOf { HazeState() }

@Immutable
data class GlassPrefs(
    val isGlassEffectEnabled: Boolean,
    val isReducedVisualEffectsEnabled: Boolean,
    val materialPreset: AppSettings.GlassMaterialPreset,
    val hazeOpacityPercent: Int,
    val blurStrengthPercent: Int,
    val noiseStrengthPercent: Int,
    val immersiveStrengthPercent: Int,
)

val LocalGlassPrefs = staticCompositionLocalOf<GlassPrefs?> { null }
