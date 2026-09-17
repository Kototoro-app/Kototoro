package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId

class DecodePlannerTest {

    private val planner = DecodePlanner()

    @Test
    fun `standard page resolves to Single plan with hardware allocator`() {
        val pageId = PageId(1L)
        val metadata = ImageSourceMetadata(size = IntSize(800, 1440), mimeType = "image/jpeg")

        val plan = planner.plan(
            pageId = pageId,
            metadata = metadata,
            viewportWidth = 1080,
            viewportHeight = 2400,
            cameraScale = 1.0f,
            capabilities = RendererCapabilities.Resolved(maxDrawableWidthPx = 4096, maxDrawableHeightPx = 4096),
        )

        assertTrue(plan is DecodePlan.Single)
        val single = plan as DecodePlan.Single
        assertEquals(IntSize(800, 1440), single.originalSize)
        assertEquals(DecodeAllocatorPolicy.HARDWARE, single.allocatorPolicy)
        assertEquals(800L * 1440L * 4L, single.estimatedResidentCostBytes)
    }

    @Test
    fun `large high-res manga page downsampled to SampledSingle without tiling`() {
        val pageId = PageId(2L)
        // High-res scan: 4000x6000 (exceeds 4096 if unscaled)
        val metadata = ImageSourceMetadata(size = IntSize(4000, 6000), mimeType = "image/png")

        val plan = planner.plan(
            pageId = pageId,
            metadata = metadata,
            viewportWidth = 1000,
            viewportHeight = 1500,
            cameraScale = 1.0f,
            capabilities = RendererCapabilities.Resolved(maxDrawableWidthPx = 4096, maxDrawableHeightPx = 4096),
        )

        // 4000px on 1000px display -> sampleSize 4 -> target 1000x1500
        assertTrue(plan is DecodePlan.SampledSingle)
        val sampled = plan as DecodePlan.SampledSingle
        assertEquals(4, sampled.sampleSize)
        assertEquals(IntSize(1000, 1500), sampled.targetSize)
        assertEquals(DecodeAllocatorPolicy.HARDWARE, sampled.allocatorPolicy)
        assertEquals(1000L * 1500L * 4L, sampled.estimatedResidentCostBytes)
    }

    @Test
    fun `ultra-long webtoon strip triggers Tiled plan with bounded working set`() {
        val pageId = PageId(3L)
        // Extreme webtoon: 800 x 32,000 (height drastically exceeds 4096 texture limit)
        val metadata = ImageSourceMetadata(size = IntSize(800, 32000), mimeType = "image/jpeg")

        val plan = planner.plan(
            pageId = pageId,
            metadata = metadata,
            viewportWidth = 1080,
            viewportHeight = 2400,
            cameraScale = 1.0f,
            capabilities = RendererCapabilities.Resolved(maxDrawableWidthPx = 4096, maxDrawableHeightPx = 4096),
        )

        assertTrue(plan is DecodePlan.Tiled)
        val tiled = plan as DecodePlan.Tiled
        assertEquals(800, tiled.tileDimension.width)
        // Strip height should be bounded
        assertTrue(tiled.tileDimension.height in 512..2048)
        // Overview LOD must downsample 32000px under 4096px limit
        val overviewH = 32000 / tiled.overviewLod.sampleSize
        assertTrue(overviewH <= 4096)
        // Working set is bounded (not 800*32000*4 = 102.4 MB!)
        assertTrue(tiled.estimatedWorkingSetBytes <= 64L * 1024L * 1024L)
    }

    @Test
    fun `animated image stays out of Tiled path with proper fallback`() {
        val pageId = PageId(4L)
        // Normal animated gif
        val normalAnimated = ImageSourceMetadata(size = IntSize(500, 500), isAnimated = true)
        val planNormal = planner.plan(
            pageId = pageId,
            metadata = normalAnimated,
            viewportWidth = 1080,
            viewportHeight = 2400,
        )
        assertTrue(planNormal is DecodePlan.AnimatedSingle)
        assertEquals(AnimatedFallback.NativeAnimated, (planNormal as DecodePlan.AnimatedSingle).fallback)

        // Oversized animated image exceeding 4096 limit
        val oversizedAnimated = ImageSourceMetadata(size = IntSize(800, 10000), isAnimated = true)
        val planOversized = planner.plan(
            pageId = pageId,
            metadata = oversizedAnimated,
            viewportWidth = 1080,
            viewportHeight = 2400,
        )
        assertTrue(planOversized is DecodePlan.AnimatedSingle)
        assertEquals(AnimatedFallback.UnsupportedTooLarge, (planOversized as DecodePlan.AnimatedSingle).fallback)
    }

    @Test
    fun `respects dynamic RendererCapabilities and safety cap`() {
        val pageId = PageId(5L)
        val metadata = ImageSourceMetadata(size = IntSize(800, 3000))

        // On a restricted canvas (max height 2048): 800x3000 exceeds 2048 -> Tiled
        val restrictedPlan = planner.plan(
            pageId = pageId,
            metadata = metadata,
            viewportWidth = 1080,
            viewportHeight = 2400,
            capabilities = RendererCapabilities.Resolved(maxDrawableWidthPx = 2048, maxDrawableHeightPx = 2048),
        )
        assertTrue(restrictedPlan is DecodePlan.Tiled)

        // On a high-end canvas (max height 8192): 800x3000 fits safely -> Single
        val highEndPlanner = DecodePlanner(tilePolicy = TilePolicy(safetyDimensionLimitPx = 8192))
        val highEndPlan = highEndPlanner.plan(
            pageId = pageId,
            metadata = metadata,
            viewportWidth = 1080,
            viewportHeight = 2400,
            capabilities = RendererCapabilities.Resolved(maxDrawableWidthPx = 8192, maxDrawableHeightPx = 8192),
        )
        assertTrue(highEndPlan is DecodePlan.Single)
    }

    @Test
    fun `split double page evaluates logical half correctly`() {
        val pageId = PageId(6L)
        // 2000x1500 split into left half (1000x1500)
        val metadata = ImageSourceMetadata(size = IntSize(2000, 1500))
        val splitLeft = ImageSourceGeometry(
            encodedSize = IntSize(2000, 1500),
            contentRect = IntRect.fromLtwh(0, 0, 1000, 1500),
        )

        val plan = planner.plan(
            pageId = pageId,
            metadata = metadata,
            geometry = splitLeft,
            viewportWidth = 1000,
            viewportHeight = 1500,
        )

        assertTrue(plan is DecodePlan.Single)
        assertEquals(IntSize(1000, 1500), (plan as DecodePlan.Single).originalSize)
    }
}
