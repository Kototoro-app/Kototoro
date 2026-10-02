package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Improvement plan 2026-09 §8.1 (CS-9): the unified planner seam.
 *
 * Both strategies — [ReaderPrediction] (continuous) and [PagedSceneResourceWindowStrategy]
 * (paged) — must satisfy the same output contract, because the ImagePipeline executes
 * windows without knowing which reading mode produced them. These tests run the shared
 * contract over BOTH strategies and pin the paged strategy's extraction oracle (it was
 * lifted verbatim from the host's hand-rolled request list).
 */
class SceneResourceWindowPlannerContractTest {

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private fun pagedSpecs(count: Int, chapterId: Long = 1L): List<PagedPageSpec> =
        (1L..count).map { index ->
            PagedPageSpec(PageId(index), PageGeometryHint.Exact(1000, 1000), chapterId, (index - 1).toInt())
        }

    /** Five 1000x1000 pages, single-page LTR: page i lives in slot i-1 spanning [i*1000, (i+1)*1000). */
    private fun pagedScene(direction: SceneReadingDirection = SceneReadingDirection.LEFT_TO_RIGHT) =
        PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1000,
            config = PagedSpreadConfig(readingDirection = direction),
            initialSpecs = pagedSpecs(5),
        )

    private val continuousPages: List<Pair<PageId, PageGeometryHint>> =
        (1L..7L).map { PageId(it) to PageGeometryHint.Exact(1000, 1000) }

    private val continuousScene = VerticalReaderScene(
        availableWidth = 1000,
        defaultViewportHeight = 1000,
        initialPages = continuousPages,
    )

    private fun continuousPlanner() = ReaderPrediction(
        config = ReaderPredictionConfig(
            staticAheadFraction = 1.0f,
            staticBehindFraction = 0.5f,
            lookaheadHorizonSeconds = 0.5f,
        ),
    )

    private fun pagedFrame(scene: PagedReaderScene, offset: Float, direction: SceneReadingDirection): ReaderFrame {
        val bounds = if (direction.isHorizontal) {
            FloatRect.fromLtwh(offset, 0f, 1000f, 1000f)
        } else {
            FloatRect.fromLtwh(0f, offset, 1000f, 1000f)
        }
        return scene.resolve(ReaderViewport(bounds))
    }

    private fun continuousFrame(offset: Float): ReaderFrame =
        continuousScene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, offset, 1000f, 1000f)))

    /**
     * The shared output contract (plan §8.1): complete snapshot, visible coverage, pageId
     * uniqueness, IMMEDIATE implies PRESENTATION_READY, SOURCE_READY only for pages not
     * visible in the frame, and every requested page exists in the scene.
     */
    private fun assertOutputContract(request: SceneResourceWindowRequest, window: ReaderResourceWindow) {
        val requestedIds = window.requests.map { it.pageId }
        val requestedIdSet = requestedIds.toSet()
        assertEquals(requestedIds.size, requestedIdSet.size, "pageIds must be unique: $requestedIds")

        val visibleIds = request.frame.visibleNodes.map { it.pageId }.toSet()
        assertTrue(
            visibleIds.all { it in requestedIdSet },
            "every visible page must be requested: visible=$visibleIds requested=$requestedIdSet",
        )

        window.requests.filter { it.priority == PrefetchPriority.IMMEDIATE }.forEach {
            assertEquals(PrefetchReadiness.PRESENTATION_READY, it.readiness, "IMMEDIATE must be PRESENTATION_READY")
        }
        window.requests.filter { it.readiness == PrefetchReadiness.SOURCE_READY }.forEach {
            assertTrue(
                it.pageId !in visibleIds,
                "SOURCE_READY must target non-visible pages (page ${it.pageId.value} is visible)",
            )
        }

        assertTrue(
            requestedIds.all { request.scene.indexOf(it) >= 0 },
            "window must not request pages absent from the scene",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Paged strategy extraction oracle (behavior lifted verbatim from the host)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `paged strategy plans immediate for active and pinned slots with slot lookahead`() {
        val scene = pagedScene()
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        val frame = pagedFrame(scene, 1000f, SceneReadingDirection.LEFT_TO_RIGHT)

        val window = planner.plan(
            SceneResourceWindowRequest(scene = scene, frame = frame, pinnedSlotIndex = 3),
        )!!

        // Active slot 1 IMMEDIATE, pinned slot 3 IMMEDIATE (before lookahead could claim it
        // at HIGH), behind slot 0 MEDIUM SOURCE_READY, ahead slot 2 HIGH SOURCE_READY.
        assertEquals(
            listOf(
                PrefetchRequest(PageId(2L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(4L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(1L), PrefetchPriority.MEDIUM, PrefetchReadiness.SOURCE_READY),
                PrefetchRequest(PageId(3L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
            ),
            window.requests,
        )
    }

    @Test
    fun `paged strategy marks partially visible neighbour slot high presentation`() {
        val scene = pagedScene()
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        // Viewport [500, 1500] spans slots 0 and 1; round(500/1000) = 1 makes slot 1 active,
        // so slot 0 is visible but not active: HIGH PRESENTATION_READY. Lookahead from slot 1
        // reaches slots 2 and 3 (slot 0 is deduped at HIGH already).
        val frame = pagedFrame(scene, 500f, SceneReadingDirection.LEFT_TO_RIGHT)

        val window = planner.plan(SceneResourceWindowRequest(scene = scene, frame = frame))!!

        assertEquals(
            listOf(
                PrefetchRequest(PageId(2L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(1L), PrefetchPriority.HIGH, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(3L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
                PrefetchRequest(PageId(4L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
            ),
            window.requests,
        )
    }

    @Test
    fun `prepared neighbours are drawable before a turn while farther slots stay source only`() {
        for (direction in SceneReadingDirection.entries) {
            for (doublePage in listOf(false, true)) {
                for (lookahead in 1..2) {
                    val scene = PagedReaderScene(
                        viewportWidth = 1000,
                        viewportHeight = 1000,
                        config = PagedSpreadConfig(isDoublePage = doublePage, readingDirection = direction),
                        initialSpecs = pagedSpecs(12),
                    )
                    val planner = PagedSceneResourceWindowStrategy(
                        lookaheadSlots = lookahead,
                        prepareAdjacentSlots = true,
                    )
                    val request = SceneResourceWindowRequest(scene, pagedFrame(scene, 2000f, direction))
                    val window = planner.plan(request)
                    assertOutputContract(request, window)
                    val byPage = window.requests.associateBy { it.pageId }
                    for (slot in scene.allSlots) {
                        val distance = kotlin.math.abs(slot.slotIndex - 2)
                        for (id in slot.pageIds) {
                            if (distance > lookahead) {
                                assertNull(byPage[id], "lookahead must remain bounded")
                            } else {
                                assertEquals(
                                    if (distance <= 1) PrefetchReadiness.PRESENTATION_READY
                                    else PrefetchReadiness.SOURCE_READY,
                                    byPage[id]?.readiness,
                                    "direction=$direction double=$doublePage slot=${slot.slotIndex}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `prepared turn participants are never downgraded across a turn cancellation or reversal`() {
        for (direction in SceneReadingDirection.entries) {
            val scene = pagedScene(direction)
            val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 1, prepareAdjacentSlots = true)
            // Start on slot 1, cancel an attempted forward turn, complete it, then return.
            val offsets = listOf(1000f, 1100f, 1499f, 1600f, 1100f, 1000f, 1500f, 2000f, 1500f, 1000f)
            for (offset in offsets) {
                val request = SceneResourceWindowRequest(scene, pagedFrame(scene, offset, direction))
                val window = planner.plan(request)
                assertOutputContract(request, window)
                for (id in listOf(PageId(2L), PageId(3L))) {
                    assertEquals(
                        PrefetchReadiness.PRESENTATION_READY,
                        window.requests.single { it.pageId == id }.readiness,
                        "a cached turn participant must remain drawable at offset=$offset direction=$direction",
                    )
                }
            }
        }
    }

    @Test
    fun `paged strategy bounds lookahead to configured slot count`() {
        val scene = pagedScene()

        val one = PagedSceneResourceWindowStrategy(lookaheadSlots = 1)
            .plan(SceneResourceWindowRequest(scene = scene, frame = pagedFrame(scene, 0f, SceneReadingDirection.LEFT_TO_RIGHT)))!!
        assertEquals(
            listOf(
                PrefetchRequest(PageId(1L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(2L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
            ),
            one.requests,
        )

        val two = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
            .plan(SceneResourceWindowRequest(scene = scene, frame = pagedFrame(scene, 0f, SceneReadingDirection.LEFT_TO_RIGHT)))!!
        assertEquals(
            listOf(
                PrefetchRequest(PageId(1L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(2L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
                PrefetchRequest(PageId(3L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
            ),
            two.requests,
        )
    }

    @Test
    fun `paged strategy coerces out-of-range offset to last slot`() {
        val scene = pagedScene()
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        val frame = pagedFrame(scene, 9000f, SceneReadingDirection.LEFT_TO_RIGHT)

        val window = planner.plan(SceneResourceWindowRequest(scene = scene, frame = frame))!!

        // Active slot clamped to 4: page 5 IMMEDIATE; everything else is behind, so pages
        // 4 and 3 are MEDIUM SOURCE_READY (ahead lookahead is out of range).
        assertEquals(
            listOf(
                PrefetchRequest(PageId(5L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(4L), PrefetchPriority.MEDIUM, PrefetchReadiness.SOURCE_READY),
                PrefetchRequest(PageId(3L), PrefetchPriority.MEDIUM, PrefetchReadiness.SOURCE_READY),
            ),
            window.requests,
        )
    }

    @Test
    fun `paged strategy requests both pages of a double slot`() {
        val scene = PagedReaderScene(
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = pagedSpecs(4),
        )
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 1)
        val frame = scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1600f, 1200f)))

        val window = planner.plan(SceneResourceWindowRequest(scene = scene, frame = frame))!!

        assertEquals(
            listOf(
                PrefetchRequest(PageId(1L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(2L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(3L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
                PrefetchRequest(PageId(4L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
            ),
            window.requests,
        )
    }

    @Test
    fun `paged strategy works on vertical paged scenes`() {
        val scene = pagedScene(SceneReadingDirection.TOP_TO_BOTTOM)
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 1)
        val frame = pagedFrame(scene, 0f, SceneReadingDirection.TOP_TO_BOTTOM)

        val window = planner.plan(SceneResourceWindowRequest(scene = scene, frame = frame))!!

        assertEquals(
            listOf(
                PrefetchRequest(PageId(1L), PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY),
                PrefetchRequest(PageId(2L), PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY),
            ),
            window.requests,
        )
    }

    @Test
    fun `paged strategy yields empty window for empty scene`() {
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1000,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
        )
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        val frame = scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 1000f)))

        val window = planner.plan(SceneResourceWindowRequest(scene = scene, frame = frame))!!

        assertTrue(window.requests.isEmpty())
    }

    @Test
    fun `paged strategy yields empty window for zero primary viewport`() {
        val scene = pagedScene()
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        val frame = scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, 0f, 0f, 1000f)))

        val window = planner.plan(SceneResourceWindowRequest(scene = scene, frame = frame))!!

        assertTrue(window.requests.isEmpty())
    }

    @Test
    fun `paged strategy rejects non paged scenes`() {
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        val frame = continuousFrame(0f)

        assertThrows(IllegalStateException::class.java) {
            planner.plan(SceneResourceWindowRequest(scene = continuousScene, frame = frame))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Continuous strategy delegation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `continuous strategy delegates to prediction and suppresses unchanged snapshots`() {
        val planner = continuousPlanner()
        val frame = continuousFrame(0f)
        val request = SceneResourceWindowRequest(scene = continuousScene, frame = frame)

        val first = planner.plan(request)
        assertTrue(first != null && first.requests.isNotEmpty())

        // Same snapshot again: the planner keeps the previous window (null).
        assertNull(planner.plan(request.copy()))

        // Motion class change produces a new window.
        val moving = planner.plan(
            SceneResourceWindowRequest(
                scene = continuousScene,
                frame = frame,
                motion = ViewportMotion(velocityY = 3000f),
            ),
        )
        assertTrue(moving != null && moving.requests.isNotEmpty())
    }

    @Test
    fun `continuous strategy plan matches predictWindowIfChanged`() {
        val config = ReaderPredictionConfig(staticAheadFraction = 1.0f, staticBehindFraction = 0.5f)
        val motions = listOf(
            ViewportMotion.Idle,
            ViewportMotion(velocityY = 1200f),
            ViewportMotion(velocityY = -4000f),
        )
        for (motion in motions) {
            for (offset in listOf(0f, 1500f, 3500f, 6500f)) {
                val frame = continuousFrame(offset)
                val viaInterface = ReaderPrediction(config).plan(
                    SceneResourceWindowRequest(scene = continuousScene, frame = frame, motion = motion),
                )
                val direct = ReaderPrediction(config).predictWindowIfChanged(continuousScene, frame, motion)
                assertEquals(direct, viaInterface)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared contract sweep over both strategies
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `both strategies satisfy the shared output contract across the scene`() {
        val paged = pagedScene()
        val pagedPlanner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        var pagedWindows = 0
        for (offset in generateSequence(-500f) { it + 250f }.takeWhile { it <= 5500f }) {
            val direction = SceneReadingDirection.LEFT_TO_RIGHT
            val frame = pagedFrame(paged, offset, direction)
            for (pinned in listOf<Int?>(null, 2, 7)) {
                val request = SceneResourceWindowRequest(scene = paged, frame = frame, pinnedSlotIndex = pinned)
                val window = pagedPlanner.plan(request)
                assertTrue(window != null, "paged strategy must always submit a window (offset=$offset pinned=$pinned)")
                assertOutputContract(request, window)
                pagedWindows++
            }
        }
        assertTrue(pagedWindows > 60, "paged sweep must cover the scene (got $pagedWindows windows)")

        val continuousPlanner = continuousPlanner()
        var continuousWindows = 0
        for (offset in generateSequence(-500f) { it + 250f }.takeWhile { it <= 7500f }) {
            for (motion in listOf(
                ViewportMotion.Idle,
                ViewportMotion(velocityY = 2500f),
                ViewportMotion(velocityY = -2500f),
            )) {
                val request = SceneResourceWindowRequest(
                    scene = continuousScene,
                    frame = continuousFrame(offset),
                    motion = motion,
                )
                val window = continuousPlanner.plan(request) ?: continue
                assertOutputContract(request, window)
                continuousWindows++
            }
        }
        assertTrue(continuousWindows > 20, "continuous sweep must cover the scene (got $continuousWindows windows)")
    }

    @Test
    fun `paged strategy window stays bounded by visible slots and lookahead`() {
        val scene = pagedScene()
        val planner = PagedSceneResourceWindowStrategy(lookaheadSlots = 2)
        for (offset in generateSequence(-500f) { it + 125f }.takeWhile { it <= 5500f }) {
            val request = SceneResourceWindowRequest(
                scene = scene,
                frame = pagedFrame(scene, offset, SceneReadingDirection.LEFT_TO_RIGHT),
                pinnedSlotIndex = 1,
            )
            val window = planner.plan(request)!!
            // 1 active + 1 pinned + 2 per lookahead slot; single-page slots hold 1 page.
            assertTrue(
                window.requests.size <= 6,
                "window must be bounded by slots, not scene size (offset=$offset size=${window.requests.size})",
            )
        }
    }
}
