package org.skepsun.kototoro.reader.image

/**
 * Levels a tiled page is painted from.
 *
 * [baseSampleSize] is the level a page needs when it is shown at fit scale, [targetSampleSize] the
 * finer level the camera currently demands (null while the camera does not ask for more detail than
 * fit). Both are resolved from the same LOD policy the sampled path uses, so a tiled page and a
 * sampled page of the same size disagree about sharpness only where tiling is unavoidable.
 *
 * Why the base does not follow the camera: a tiled page in a paged scene is usually *not* the page
 * the user magnified. Sizing the base from the global camera scale gave every neighbour a level-zero
 * grid, and a frame painting a page at fit scale from level zero costs `screenPx / density^2` image
 * pixels - 45M for one 3.55M-pixel screen at density 0.31, against 3M at the level fit actually needs
 * (device A/B, 2026-09-19: 43 tiles / 172MB per layer, CPU P99 12.1ms at 2x, versus 8ms for the same
 * pages as sampled bitmaps).
 *
 * The lattice itself deliberately stays at [TilePolicy.preferredTileEdgePx]: enlarging tiles to one
 * viewport each (Telephoto's rule) cut the painted tile count 4x on the same fixture but raised
 * CPU P99 from 12.1ms to 29.8ms, because a frame's texture-upload spike grows with the unit.
 */
data class TileLadder(
    val baseSampleSize: Int,
    val targetSampleSize: Int?,
) {
    init {
        require(baseSampleSize >= 1 && (baseSampleSize and (baseSampleSize - 1)) == 0) {
            "baseSampleSize must be a positive power of 2: $baseSampleSize"
        }
        require(targetSampleSize == null || targetSampleSize >= 1) {
            "targetSampleSize must be positive: $targetSampleSize"
        }
        require(targetSampleSize == null || (targetSampleSize and (targetSampleSize - 1)) == 0) {
            "targetSampleSize must be a power of 2: $targetSampleSize"
        }
        require(targetSampleSize == null || targetSampleSize < baseSampleSize) {
            "targetSampleSize must be finer than baseSampleSize: $targetSampleSize vs $baseSampleSize"
        }
    }

    val hasTarget: Boolean get() = targetSampleSize != null
}

/**
 * Resolves the tile ladder for a page of [sourceContentWidthPx] logical pixels shown in a viewport
 * [viewportWidthPx] wide under [cameraScale].
 *
 * [currentSampleSize] is the level the page is painted from right now; passing it applies the LOD
 * policy's hysteresis, so a pinch that hovers on a level boundary does not rebuild a layer per settle.
 */
fun resolveTileLadder(
    sourceContentWidthPx: Int,
    viewportWidthPx: Int,
    cameraScale: Float,
    currentSampleSize: Int? = null,
    lodPolicy: ReaderLodPolicy = ReaderLodPolicy(),
): TileLadder {
    require(sourceContentWidthPx > 0) { "sourceContentWidthPx must be > 0: $sourceContentWidthPx" }
    require(viewportWidthPx > 0) { "viewportWidthPx must be > 0: $viewportWidthPx" }
    require(cameraScale > 0f) { "cameraScale must be > 0: $cameraScale" }

    val fitSampleSize = lodPolicy
        .resolveLod(sourceContentWidthPx, viewportWidthPx, cameraScale = 1f)
        .sampleSize
    val plainCameraSampleSize = lodPolicy
        .resolveLod(sourceContentWidthPx, viewportWidthPx, cameraScale)
        .sampleSize
    // Hysteresis applies *within* the magnified regime only. Letting it decide whether a target
    // exists at all would keep a level-zero layer alive at fit scale (the base already carries that
    // level), which is dead weight - and the band would need the camera to fall well below fit
    // before dropping it, since the policy's downgrade threshold is a full level wide.
    val cameraSampleSize = if (plainCameraSampleSize < fitSampleSize && currentSampleSize != null) {
        lodPolicy.resolveLod(
            sourceContentWidthPx = sourceContentWidthPx,
            baseDisplayWidthPx = viewportWidthPx,
            cameraScale = cameraScale,
            currentLod = lodSpecOf(currentSampleSize),
        ).sampleSize
    } else {
        plainCameraSampleSize
    }

    // Zooming out past fit genuinely needs less detail than fit, so the base coarsens with it;
    // zooming in must not, and is served by the target instead.
    return TileLadder(
        baseSampleSize = maxOf(fitSampleSize, plainCameraSampleSize),
        targetSampleSize = cameraSampleSize.takeIf { it < fitSampleSize },
    )
}

private fun lodSpecOf(sampleSize: Int): LodSpec = LodSpec(
    level = ReaderLodPolicy.calculateLodLevel(sampleSize),
    sampleSize = sampleSize,
    targetPixelScale = 1.0f / sampleSize.toFloat(),
)
