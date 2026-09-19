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
    fun `a mid-zoom oversized page stays one sampled bitmap instead of tiling`() {
        // The fit-height cliff: a 6000x9000 page at a 1.44x camera resolves to sampleSize 2, i.e. a
        // 3000x4500 decode. With a 4096 safety ceiling that decode was rejected as too big and the
        // page fell into the tiled path, which paints many textures every frame (452ms CPU P99 on
        // device). It fits memory at 54MB, so the ceiling is what decided it.
        val plan = DecodePlanner().plan(
            pageId = PageId(7L),
            metadata = ImageSourceMetadata(size = IntSize(6000, 9000)),
            viewportWidth = 1280,
            viewportHeight = 2772,
            cameraScale = 1.444f,
        )

        assertTrue(plan is DecodePlan.SampledSingle, "expected a sampled decode, got $plan")
        assertEquals(2, (plan as DecodePlan.SampledSingle).sampleSize)
    }

    @Test
    fun `genuinely too-large decodes still tile`() {
        // At 4x the plan needs level zero: 6000x9000 is 216MB, past the 64MB working set, so tiling
        // stays correct there. (At 2.5x the drawing-density tolerance resolves sampleSize 2, which
        // fits as one bitmap - that is the case the ceiling change deliberately enables.)
        val plan = DecodePlanner().plan(
            pageId = PageId(8L),
            metadata = ImageSourceMetadata(size = IntSize(6000, 9000)),
            viewportWidth = 1280,
            viewportHeight = 2772,
            cameraScale = 4f,
        )

        assertTrue(plan is DecodePlan.Tiled, "expected tiling, got $plan")
    }

    @Test
    fun `tiled lattice stays at the preferred tile edge for a 2D page`() {
        // Telephoto-sized tiles (imageSize * sampleSize / baseSampleSize) were implemented and
        // measured on device, and lost: level-zero tiles of 1500x2250 cut the painted tile count
        // 43 -> 12 per layer and decode requests 282 -> 191 at 2x, but CPU P99 rose 12.1 -> 29.8ms,
        // resident tiles 293 -> 424MB and GPU 385 -> 554MB, because a frame's texture-upload spike
        // grows with the unit. Textures stay small; the lattice stays fixed.
        val plan = DecodePlanner().plan(
            pageId = PageId(9L),
            metadata = ImageSourceMetadata(size = IntSize(6000, 9000)),
            viewportWidth = 1280,
            viewportHeight = 2772,
            cameraScale = 4f,
        )

        assertTrue(plan is DecodePlan.Tiled, "expected tiling, got $plan")
        val tiled = plan as DecodePlan.Tiled
        assertEquals(1, tiled.lod.sampleSize, "level zero is the 1:1 level")
        assertEquals(IntSize(1024, 1024), tiled.tileDimension)
    }

    @Test
    fun `tile cost is the decoded size, not the logical coverage`() {
        // A strip tile at a coarse level covers 2048x512 of page but decodes to 1024x256: charging
        // the logical rectangle tells the residency ledger a 1MB bitmap costs 4MB, and the working
        // set estimate then overstates what the tiled path holds.
        val plan = DecodePlanner().plan(
            pageId = PageId(10L),
            metadata = ImageSourceMetadata(size = IntSize(2048, 20000)),
            viewportWidth = 800,
            viewportHeight = 1600,
            cameraScale = 1f,
        )

        assertTrue(plan is DecodePlan.Tiled, "expected tiling, got $plan")
        val tiled = plan as DecodePlan.Tiled
        assertEquals(2, tiled.lod.sampleSize)
        assertEquals(IntSize(2048, 512), tiled.tileDimension)
        assertEquals(1024L * 256L * 4L, tiled.estimatedTileBytes)
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
