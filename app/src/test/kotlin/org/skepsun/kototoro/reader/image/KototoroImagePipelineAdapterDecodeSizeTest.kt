package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId

/**
 * The decode plan's LOD has to reach the image request.
 *
 * `DecodePlanner` already decides that an oversized page should be decoded at a sampled size
 * (a 6000x9000 page at a 1280-wide viewport resolves to roughly 1500x2250, about 13.5MB, instead
 * of 216MB at the original resolution). When the request carries no size constraint, Coil decodes
 * the original resolution instead, which is how a single large page turned into a multi-hundred
 * megabyte spike in the paged benchmark.
 */
class KototoroImagePipelineAdapterDecodeSizeTest {

    private val pageId = PageId(1L)

    @Test
    fun `sampled plans pass their target size to the image request`() {
        val plan = DecodePlan.SampledSingle(
            pageId = pageId,
            targetSize = IntSize(1500, 2250),
            sampleSize = 4,
            pixelUsage = PixelUsage.DISPLAY_ONLY,
            allocatorPolicy = DecodeAllocatorPolicy.resolve(PixelUsage.DISPLAY_ONLY, allowHardware = false),
            estimatedResidentCostBytes = 13_500_000L,
        )

        assertEquals(IntSize(1500, 2250), plan.requestedDecodeSize())
    }

    @Test
    fun `tiled and exact plans keep no single-bitmap size constraint`() {
        // Tiled pages are drawn from the tile store, and a single plan is already exactly one
        // bitmap at the source resolution, so constraining the request would only duplicate work.
        val tiled = DecodePlan.Tiled(
            pageId = pageId,
            lod = LodSpec(level = 0, sampleSize = 1, targetPixelScale = 1f),
            overviewLod = LodSpec(level = 3, sampleSize = 8, targetPixelScale = 0.125f),
            tileDimension = IntSize(1024, 1024),
            estimatedTileBytes = 4_194_304L,
            estimatedWorkingSetBytes = 25_165_824L,
        )
        val single = DecodePlan.Single(
            pageId = pageId,
            originalSize = IntSize(800, 1200),
            pixelUsage = PixelUsage.DISPLAY_ONLY,
            allocatorPolicy = DecodeAllocatorPolicy.resolve(PixelUsage.DISPLAY_ONLY, allowHardware = false),
            estimatedResidentCostBytes = 3_840_000L,
        )

        assertNull(tiled.requestedDecodeSize())
        assertNull(single.requestedDecodeSize())
        assertNull((null as DecodePlan?).requestedDecodeSize())
    }
}
