package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class PanoramaAnimationDurations(
    val scaleMillis: Int,
    val horizontalPanMillis: Int,
    val verticalPanMillis: Int,
)

data class PanoramaAnimationMotion(
    val initialScale: Float,
    val targetScale: Float,
    val horizontalPan: Dp,
    val verticalPan: Dp,
)

fun panoramaAnimationDurations(speedPercent: Int): PanoramaAnimationDurations {
    val speedFactor = speedPercent.coerceIn(
        PanoramaAnimationSpeedMinPercent,
        PanoramaAnimationSpeedMaxPercent,
    ) / 100f
    return PanoramaAnimationDurations(
        scaleMillis = (7000 / speedFactor).toInt().coerceAtLeast(900),
        horizontalPanMillis = (8000 / speedFactor).toInt().coerceAtLeast(1000),
        verticalPanMillis = (6000 / speedFactor).toInt().coerceAtLeast(800),
    )
}

fun panoramaAnimationMotion(): PanoramaAnimationMotion = PanoramaAnimationMotion(
    initialScale = 1.14f,
    targetScale = 1.24f,
    horizontalPan = 32.dp,
    verticalPan = 18.dp,
)

const val PanoramaAnimationSpeedMinPercent = 25
const val PanoramaAnimationSpeedMaxPercent = 200
