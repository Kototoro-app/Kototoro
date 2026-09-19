package org.skepsun.kototoro.reader.image

/**
 * Tunable policy defining tile dimensions, pixel budgets, and safety limits.
 *
 * Conforms to ADR 0002 Constraint 2:
 * Separated from hardware [RendererCapabilities].
 */
data class TilePolicy(
    /** Preferred nominal edge length for square tiles or strip segment heights. */
    val preferredTileEdgePx: Int = DEFAULT_PREFERRED_TILE_EDGE_PX,

    /** Maximum allowed decoded pixels per single tile (e.g. 1024x1024 = 1 Mpixels ~4MB in ARGB_8888). */
    val maxDecodedTilePixels: Int = DEFAULT_MAX_DECODED_TILE_PIXELS,

    /**
     * Ceiling on a single decoded dimension before the planner forces tiling or downsampling.
     *
     * Sized to what Android GPUs actually accept (8K textures are universal on API 26+; ADR 0002
     * Phase 1D observed the hard ceiling at 16384px), not to the most conservative value: at 4096 a
     * 6000x9000 page could only be a single bitmap at the fit LOD, so every mid-zoom view
     * (sampleSize 2, i.e. 3000x4500) was pushed into the tiled path, and tiled pages paint many
     * textures per frame - 452ms CPU P99 on device against 7ms for the same page as one bitmap.
     */
    val safetyDimensionLimitPx: Int = DEFAULT_SAFETY_DIMENSION_LIMIT_PX,

    /** Default working set resident cost budget in bytes (e.g., 64MB). */
    val defaultWorkingSetCostBudgetBytes: Long = DEFAULT_WORKING_SET_COST_BUDGET_BYTES,
) {
    init {
        require(preferredTileEdgePx > 0) { "preferredTileEdgePx must be > 0: $preferredTileEdgePx" }
        require(maxDecodedTilePixels > 0) { "maxDecodedTilePixels must be > 0: $maxDecodedTilePixels" }
        require(safetyDimensionLimitPx > 0) { "safetyDimensionLimitPx must be > 0: $safetyDimensionLimitPx" }
        require(defaultWorkingSetCostBudgetBytes > 0) { "defaultWorkingSetCostBudgetBytes must be > 0: $defaultWorkingSetCostBudgetBytes" }
    }

    companion object {
        const val DEFAULT_PREFERRED_TILE_EDGE_PX = 1024
        const val DEFAULT_MAX_DECODED_TILE_PIXELS = 1024 * 1024 // 1,048,576 pixels
        const val DEFAULT_SAFETY_DIMENSION_LIMIT_PX = 8192
        const val DEFAULT_WORKING_SET_COST_BUDGET_BYTES = 64L * 1024L * 1024L // 64 MB
    }
}
