package org.skepsun.kototoro.reader.image

import kotlin.math.ceil
import kotlin.math.max

/**
 * Level of Detail (LOD) specification for decoded bitmap or tiled regions.
 */
data class LodSpec(
    val level: Int,
    val sampleSize: Int,
    val targetPixelScale: Float,
) {
    init {
        require(level >= 0) { "level must be >= 0: $level" }
        require(sampleSize >= 1 && (sampleSize and (sampleSize - 1)) == 0) {
            "sampleSize must be a positive power of 2: $sampleSize"
        }
        require(targetPixelScale > 0f) { "targetPixelScale must be > 0: $targetPixelScale" }
    }
}

/**
 * Calculates LOD and power-of-two downsampling factor ([LodSpec]) based on
 * target display pixels and camera scale.
 *
 * Implements:
 * 1. Target-based decode sizing:
 *    `targetDecodeWidthPx = min(sourceWidth, ceil(displayWidth * cameraScale * qualityOverscan))`
 * 2. Downsampling: `sampleSize = largest power of two <= ratio * (1 + resolutionToleranceFraction)`,
 *    so a decode may fall short of the demand by that fraction instead of doubling its pixels for
 *    the last few percent - the difference between level-zero tiles and half-size ones at zoom.
 * 3. Hysteresis: applies a hysteresis band ([hysteresisFraction]) to prevent
 *    frequent LOD thrashing during pinch-zoom interactions.
 */
class ReaderLodPolicy(
    val qualityOverscan: Float = DEFAULT_QUALITY_OVERSCAN,
    val hysteresisFraction: Float = DEFAULT_HYSTERESIS_FRACTION,
    /**
     * How far the decoded width may fall short of the demanded width before the policy pays for
     * the next sharper sample size. Zero restores the strict "never undersample" behaviour.
     */
    val resolutionToleranceFraction: Float = DEFAULT_RESOLUTION_TOLERANCE_FRACTION,
) {
    init {
        require(qualityOverscan >= 1.0f) { "qualityOverscan must be >= 1.0: $qualityOverscan" }
        require(hysteresisFraction in 0.0f..0.5f) { "hysteresisFraction must be in 0.0..0.5: $hysteresisFraction" }
        require(resolutionToleranceFraction in 0.0f..0.5f) {
            "resolutionToleranceFraction must be in 0.0..0.5: $resolutionToleranceFraction"
        }
    }

    /**
     * Resolves the appropriate [LodSpec] for the given source and viewport dimensions.
     *
     * @param sourceContentWidthPx Intrinsic width of source image content in pixels.
     * @param baseDisplayWidthPx Physical width of the page when displayed at 1.0x scale.
     * @param cameraScale Current viewport zoom / camera scale (1.0 = fit width, 2.0 = 2x zoom).
     * @param currentLod Optional previous [LodSpec] to apply hysteresis against.
     */
    fun resolveLod(
        sourceContentWidthPx: Int,
        baseDisplayWidthPx: Int,
        cameraScale: Float,
        currentLod: LodSpec? = null,
    ): LodSpec {
        require(sourceContentWidthPx > 0) { "sourceContentWidthPx must be > 0: $sourceContentWidthPx" }
        require(baseDisplayWidthPx > 0) { "baseDisplayWidthPx must be > 0: $baseDisplayWidthPx" }
        require(cameraScale > 0f) { "cameraScale must be > 0: $cameraScale" }

        val rawTargetWidth = baseDisplayWidthPx.toFloat() * cameraScale * qualityOverscan
        val targetDecodeWidthPx = minOf(
            sourceContentWidthPx,
            max(1, ceil(rawTargetWidth).toInt()),
        )

        val rawSampleRatio = sourceContentWidthPx.toFloat() / targetDecodeWidthPx.toFloat()

        // Calculate power-of-two sampleSize that covers the target within the tolerance
        val idealSampleSize = calculatePowerOfTwoSampleSize(rawSampleRatio, resolutionToleranceFraction)

        val finalSampleSize = if (currentLod != null && hysteresisFraction > 0f) {
            applyHysteresis(
                idealSampleSize = idealSampleSize,
                currentSampleSize = currentLod.sampleSize,
                rawSampleRatio = rawSampleRatio,
            )
        } else {
            idealSampleSize
        }

        val level = calculateLodLevel(finalSampleSize)
        val targetScale = 1.0f / finalSampleSize.toFloat()

        return LodSpec(
            level = level,
            sampleSize = finalSampleSize,
            targetPixelScale = targetScale,
        )
    }

    private fun calculatePowerOfTwoSampleSize(ratio: Float, tolerance: Float): Int {
        // Decoded width is source/sampleSize and the demand is source/ratio, so a sampleSize is
        // acceptable while sampleSize <= ratio * (1 + tolerance): the decode may fall short by up
        // to the tolerance, but never by more than that.
        val effectiveRatio = ratio * (1.0f + tolerance)
        if (effectiveRatio < 2.0f) return 1
        val floored = effectiveRatio.toInt()
        return Integer.highestOneBit(floored)
    }

    private fun applyHysteresis(
        idealSampleSize: Int,
        currentSampleSize: Int,
        rawSampleRatio: Float,
    ): Int {
        if (idealSampleSize == currentSampleSize) return currentSampleSize

        return if (idealSampleSize < currentSampleSize) {
            // Trying to upgrade to higher resolution (smaller sampleSize).
            // Require ratio to drop below threshold * (1 - hysteresisFraction) before upgrading.
            val upgradeThreshold = currentSampleSize.toFloat() * (1.0f - hysteresisFraction)
            if (rawSampleRatio <= upgradeThreshold) idealSampleSize else currentSampleSize
        } else {
            // Trying to downgrade to lower resolution (larger sampleSize).
            // Require ratio to rise above threshold * (1 + hysteresisFraction) before downgrading.
            val downgradeThreshold = (currentSampleSize.toFloat() * 2.0f) * (1.0f + hysteresisFraction)
            if (rawSampleRatio >= downgradeThreshold) idealSampleSize else currentSampleSize
        }
    }

    companion object {
        const val DEFAULT_QUALITY_OVERSCAN = 1.0f
        const val DEFAULT_HYSTERESIS_FRACTION = 0.15f

        /**
         * Default drawing-density tolerance. 15% keeps the worst case a 1.15x upscale, which is
         * imperceptible next to the 4x pixel cost of the next sharper sample size.
         */
        const val DEFAULT_RESOLUTION_TOLERANCE_FRACTION = 0.15f

        fun calculateLodLevel(sampleSize: Int): Int {
            // sampleSize 1 -> level 4, sampleSize 2 -> level 3, sampleSize 4 -> level 2, ...
            val power = 31 - Integer.numberOfLeadingZeros(sampleSize)
            return maxOf(0, 4 - power)
        }
    }
}
