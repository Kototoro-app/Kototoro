package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId

/**
 * Fallback behavior for animated images (GIF, Animated WebP, Animated AVIF).
 */
sealed interface AnimatedFallback {
    /** Standard native animated playback when within safe dimensions. */
    data object NativeAnimated : AnimatedFallback

    /** Decode only the first frame as a static bitmap if animation would exceed memory limits. */
    data object StaticFirstFrame : AnimatedFallback

    /** Explicit unsupported / error state if animated source exceeds hardware limits and cannot be sliced. */
    data object UnsupportedTooLarge : AnimatedFallback
}

/**
 * Concrete decoding plan determined by [DecodePlanner].
 *
 * Conforms to ADR 0002 Constraint 2:
 * 1. Single: full-resolution decode within limits.
 * 2. SampledSingle: downsampled decode meeting display requirements without tiling overhead.
 * 3. Tiled: region sliced into LOD tiles for ultra-long strips or high-magnification zoom.
 * 4. AnimatedSingle: safe handling of animated content.
 */
sealed interface DecodePlan {
    val pageId: PageId
    val estimatedResidentCostBytes: Long

    data class Single(
        override val pageId: PageId,
        val originalSize: IntSize,
        val pixelUsage: PixelUsage,
        val allocatorPolicy: DecodeAllocatorPolicy,
        override val estimatedResidentCostBytes: Long,
    ) : DecodePlan

    data class SampledSingle(
        override val pageId: PageId,
        val targetSize: IntSize,
        val sampleSize: Int,
        val pixelUsage: PixelUsage,
        val allocatorPolicy: DecodeAllocatorPolicy,
        override val estimatedResidentCostBytes: Long,
    ) : DecodePlan

    data class Tiled(
        override val pageId: PageId,
        val lod: LodSpec,
        val overviewLod: LodSpec,
        val tileDimension: IntSize,
        val estimatedTileBytes: Long,
        val estimatedWorkingSetBytes: Long,
        override val estimatedResidentCostBytes: Long = estimatedWorkingSetBytes,
    ) : DecodePlan

    data class AnimatedSingle(
        override val pageId: PageId,
        val originalSize: IntSize,
        val fallback: AnimatedFallback,
        override val estimatedResidentCostBytes: Long,
    ) : DecodePlan
}
