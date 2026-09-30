package org.skepsun.kototoro.core.image

import kotlin.math.sqrt

/** Fits all resident animation frames within a per-image budget without integer overflow. */
internal fun resolveAvifAnimatedDecodeSize(
    width: Int,
    height: Int,
    frameCount: Int,
    bytesPerPixel: Int,
    memoryBudgetBytes: Long,
): Pair<Int, Int>? {
    require(width > 0 && height > 0 && frameCount > 0 && bytesPerPixel > 0)
    val maxPixelsPerFrame = memoryBudgetBytes / bytesPerPixel / frameCount
    if (maxPixelsPerFrame < 1) return null
    val pixels = width.toLong() * height
    if (pixels <= maxPixelsPerFrame) return width to height

    val scale = sqrt(maxPixelsPerFrame.toDouble() / pixels)
    val sampledWidth = (width * scale).toInt().coerceAtLeast(1)
    val sampledHeight = (height * scale).toInt().coerceAtLeast(1)
        .coerceAtMost((maxPixelsPerFrame / sampledWidth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    // Extremely wide images can round the height to one while the width still exceeds the budget.
    return if (sampledHeight > 0) sampledWidth to sampledHeight else maxPixelsPerFrame.toInt() to 1
}
