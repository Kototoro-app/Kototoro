package org.skepsun.kototoro.settings.compose

enum class PanoramaLayoutMode {
    FULL_SCREEN,
    HALF_SCREEN,
}

data class PanoramaEffectValues(
    val blurPercent: Int,
    val transitionRangePercent: Int,
    val topOpacityPercent: Int,
)

enum class PanoramaEffectPreset {
    CLEAR,
    BALANCED,
    SOFT,
    CUSTOM,
    ;

    fun valuesFor(mode: PanoramaLayoutMode): PanoramaEffectValues? = when (this) {
        CLEAR -> when (mode) {
            PanoramaLayoutMode.FULL_SCREEN -> PanoramaEffectValues(10, 55, 90)
            PanoramaLayoutMode.HALF_SCREEN -> PanoramaEffectValues(10, 40, 90)
        }
        BALANCED -> when (mode) {
            PanoramaLayoutMode.FULL_SCREEN -> PanoramaEffectValues(35, 100, 90)
            PanoramaLayoutMode.HALF_SCREEN -> PanoramaEffectValues(30, 75, 90)
        }
        SOFT -> when (mode) {
            PanoramaLayoutMode.FULL_SCREEN -> PanoramaEffectValues(60, 100, 90)
            PanoramaLayoutMode.HALF_SCREEN -> PanoramaEffectValues(55, 100, 90)
        }
        CUSTOM -> null
    }
}

fun resolvePanoramaEffectPreset(
    mode: PanoramaLayoutMode,
    blurPercent: Int,
    transitionRangePercent: Int,
    topOpacityPercent: Int,
): PanoramaEffectPreset {
    return PanoramaEffectPreset.entries.firstOrNull { preset ->
        preset.valuesFor(mode) == PanoramaEffectValues(blurPercent, transitionRangePercent, topOpacityPercent)
    } ?: PanoramaEffectPreset.CUSTOM
}
