package org.skepsun.kototoro.core.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class GlassPrefs(
    val isGlassEffectEnabled: Boolean,
    val isReducedVisualEffectsEnabled: Boolean,
    val immersiveStrengthPercent: Int,
    val edgeBlurRadiusDp: Int = 0,
    val isTopImmersiveEdgeEnabled: Boolean = true,
    val isBottomImmersiveEdgeEnabled: Boolean = false,
)

val LocalGlassPrefs = staticCompositionLocalOf<GlassPrefs?> { null }
