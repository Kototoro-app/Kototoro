package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId

/**
 * Pure, testable decision engine planning image decoding strategy.
 *
 * Implements ADR 0002 Constraint 2:
 * Evaluates source dimensions, display geometry, zoom scale, renderer capabilities,
 * and memory budgets to select among [DecodePlan.Single], [DecodePlan.SampledSingle],
 * [DecodePlan.Tiled], or [DecodePlan.AnimatedSingle].
 *
 * Zero Android UI, Bitmap, or Canvas dependencies.
 */
class DecodePlanner(
    val tilePolicy: TilePolicy = TilePolicy(),
    val lodPolicy: ReaderLodPolicy = ReaderLodPolicy(),
) {

    /**
     * Determines the optimal [DecodePlan] for the given page and environment.
     */
    fun plan(
        pageId: PageId,
        metadata: ImageSourceMetadata,
        geometry: ImageSourceGeometry = ImageSourceGeometry(metadata.size),
        viewportWidth: Int,
        viewportHeight: Int,
        cameraScale: Float = 1.0f,
        pixelUsage: PixelUsage = PixelUsage.DISPLAY_ONLY,
        capabilities: RendererCapabilities = RendererCapabilities.Unknown,
        currentLod: LodSpec? = null,
        format: RasterFormat = RasterFormat.ARGB_8888,
    ): DecodePlan {
        require(viewportWidth > 0) { "viewportWidth must be > 0: $viewportWidth" }
        require(viewportHeight > 0) { "viewportHeight must be > 0: $viewportHeight" }
        require(cameraScale > 0f) { "cameraScale must be > 0: $cameraScale" }

        val logicalSize = geometry.logicalSize
        // The policy is the single source of truth for the ceiling: while the renderer has not
        // reported its limits yet, the planner has to assume the same value it would enforce anyway,
        // or an unresolved capability silently forces tiling for mid-zoom pages.
        val limits = capabilities.effectiveLimits(
            safetyLimitPx = tilePolicy.safetyDimensionLimitPx,
            fallbackDefaultPx = tilePolicy.safetyDimensionLimitPx,
        )
        val maxDrawableW = limits.width
        val maxDrawableH = limits.height
        val bytesPerPixel = format.estimatedBytesPerPixel.toLong()

        // 1. Handle animated images (GIF / Animated WebP / Animated AVIF)
        if (metadata.isAnimated) {
            val exceedsLimit = logicalSize.width > maxDrawableW || logicalSize.height > maxDrawableH
            val fallback = if (exceedsLimit) {
                AnimatedFallback.UnsupportedTooLarge
            } else {
                AnimatedFallback.NativeAnimated
            }
            val estBytes = logicalSize.width.toLong() * logicalSize.height.toLong() * bytesPerPixel
            return DecodePlan.AnimatedSingle(
                pageId = pageId,
                originalSize = logicalSize,
                fallback = fallback,
                estimatedResidentCostBytes = estBytes,
            )
        }

        // 2. Resolve LOD based on target display pixels
        val lod = lodPolicy.resolveLod(
            sourceContentWidthPx = logicalSize.width,
            baseDisplayWidthPx = viewportWidth,
            cameraScale = cameraScale,
            currentLod = currentLod,
        )

        val sampleSize = lod.sampleSize
        val targetW = maxOf(1, logicalSize.width / sampleSize)
        val targetH = maxOf(1, logicalSize.height / sampleSize)
        val targetSize = IntSize(targetW, targetH)
        val estimatedSingleBytes = targetW.toLong() * targetH.toLong() * bytesPerPixel

        val allocatorPolicy = DecodeAllocatorPolicy.resolve(
            usage = pixelUsage,
            allowHardware = capabilities is RendererCapabilities.Resolved,
        )

        // 3. Evaluate if downsampled or full single bitmap fits within hardware & memory limits
        val fitsInHardware = targetW <= maxDrawableW && targetH <= maxDrawableH
        // A single page may use the whole working set. Capping it at a fraction (tried: a quarter)
        // pushes mid-zoom pages with a 54MB decode into the tiled path, and tiles are drawn as many
        // textures every frame: the fit-height page measured 452ms CPU P99 at 1.5x that way, against
        // 7ms when it stays one sampled bitmap. The budget already bounds the sampled case.
        val fitsInMemory = estimatedSingleBytes <= tilePolicy.defaultWorkingSetCostBudgetBytes
        val forcedTile = pixelUsage == PixelUsage.REGION_TILE

        if (fitsInHardware && fitsInMemory && !forcedTile) {
            return if (sampleSize == 1) {
                DecodePlan.Single(
                    pageId = pageId,
                    originalSize = logicalSize,
                    pixelUsage = pixelUsage,
                    allocatorPolicy = allocatorPolicy,
                    estimatedResidentCostBytes = estimatedSingleBytes,
                )
            } else {
                DecodePlan.SampledSingle(
                    pageId = pageId,
                    targetSize = targetSize,
                    sampleSize = sampleSize,
                    pixelUsage = pixelUsage,
                    allocatorPolicy = allocatorPolicy,
                    estimatedResidentCostBytes = estimatedSingleBytes,
                )
            }
        }

        // 4. Fallback to Tiled plan: calculate overview LOD and tile specs
        val overviewLod = resolveOverviewLod(logicalSize, limits)
        val tileDimension = resolveTileDimension(logicalSize)
        val singleTileBytes = tileDimension.width.toLong() * tileDimension.height.toLong() * bytesPerPixel
        // Estimated working set: 4-6 tiles covering the viewport plus 1 lookahead
        val estimatedWorkingSet = minOf(
            tilePolicy.defaultWorkingSetCostBudgetBytes,
            singleTileBytes * 6L,
        )

        return DecodePlan.Tiled(
            pageId = pageId,
            lod = lod,
            overviewLod = overviewLod,
            tileDimension = tileDimension,
            estimatedTileBytes = singleTileBytes,
            estimatedWorkingSetBytes = estimatedWorkingSet,
        )
    }

    private fun resolveOverviewLod(sourceSize: IntSize, limits: IntSize): LodSpec {
        var sampleSize = 1
        var w = sourceSize.width
        var h = sourceSize.height
        while ((w > limits.width || h > limits.height) && sampleSize < 64) {
            sampleSize *= 2
            w = maxOf(1, sourceSize.width / sampleSize)
            h = maxOf(1, sourceSize.height / sampleSize)
        }
        return LodSpec(
            level = ReaderLodPolicy.calculateLodLevel(sampleSize),
            sampleSize = sampleSize,
            targetPixelScale = 1.0f / sampleSize.toFloat(),
        )
    }

    private fun resolveTileDimension(logicalSize: IntSize): IntSize {
        val preferredEdge = tilePolicy.preferredTileEdgePx
        val maxPixels = tilePolicy.maxDecodedTilePixels

        // For webtoon, prefer full-width strip tiles if width fits comfortably
        return if (logicalSize.width <= preferredEdge * 2) {
            val tileW = logicalSize.width
            val maxH = maxPixels / tileW
            val tileH = minOf(maxH, preferredEdge * 2).coerceAtLeast(preferredEdge / 2)
            IntSize(tileW, tileH)
        } else {
            // 2D grid tiles
            val tileW = minOf(logicalSize.width, preferredEdge)
            val tileH = minOf(logicalSize.height, preferredEdge)
            IntSize(tileW, tileH)
        }
    }
}
