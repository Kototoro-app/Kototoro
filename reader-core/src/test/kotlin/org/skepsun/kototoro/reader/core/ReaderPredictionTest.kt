package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPredictionTest {

    private val pages = listOf(
        PageId(1L) to PageGeometryHint.Exact(1000, 1000), // 0..1000
        PageId(2L) to PageGeometryHint.Exact(1000, 1000), // 1000..2000
        PageId(3L) to PageGeometryHint.Exact(1000, 1000), // 2000..3000
        PageId(4L) to PageGeometryHint.Exact(1000, 1000), // 3000..4000
        PageId(5L) to PageGeometryHint.Exact(1000, 1000), // 4000..5000
        PageId(6L) to PageGeometryHint.Exact(1000, 1000), // 5000..6000
        PageId(7L) to PageGeometryHint.Exact(1000, 1000), // 6000..7000
    )

    private val scene = VerticalReaderScene(
        availableWidth = 1000,
        defaultViewportHeight = 1000,
        initialPages = pages,
    )

    private val predictor = ReaderPrediction(
        config = ReaderPredictionConfig(
            staticAheadFraction = 1.0f,
            staticBehindFraction = 0.5f,
            lookaheadHorizonSeconds = 0.5f,
        ),
    )

    @Test
    fun `predict returns empty when viewport has zero size`() {
        val zeroVp = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 0f, 0f))
        val result = predictor.predict(scene, zeroVp)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `predict tags visible page as IMMEDIATE and next pages as HIGH and MEDIUM when idle`() {
        // Viewport at 1200..2200 (intersects Page 2: 1000..2000 and Page 3: 2000..3000)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1200f, 1000f, 1000f))
        val requests = predictor.predict(scene, viewport, ViewportMotion.Idle)

        val immediate = requests.filter { it.priority == PrefetchPriority.IMMEDIATE }.map { it.pageId }
        val high = requests.filter { it.priority == PrefetchPriority.HIGH }.map { it.pageId }
        val medium = requests.filter { it.priority == PrefetchPriority.MEDIUM }.map { it.pageId }

        // Visible nodes are Page 2 and Page 3
        assertEquals(listOf(PageId(2L), PageId(3L)), immediate)

        // Static ahead: 1000px ahead (up to 3200 -> Page 4: 3000..4000)
        // Static behind: 500px behind (down to 700 -> Page 1: 0..1000)
        assertTrue(PageId(1L) in high)
        assertTrue(PageId(4L) in high)

        // Medium window: further ahead (Page 5)
        assertTrue(PageId(5L) in medium)
        assertTrue(
            requests.filter { it.priority != PrefetchPriority.MEDIUM }
                .all { it.readiness == PrefetchReadiness.PRESENTATION_READY },
        )
        assertTrue(
            requests.filter { it.priority == PrefetchPriority.MEDIUM }
                .all { it.readiness == PrefetchReadiness.SOURCE_READY },
        )
    }

    @Test
    fun `predict expands lookahead dynamically during rapid downward fling`() {
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1000f, 1000f, 1000f))
        // High velocity forward: 4000 px/s * 0.5s = 2000px extra ahead
        val fastMotion = ViewportMotion(velocityY = 4000f)
        val requests = predictor.predict(scene, viewport, fastMotion)

        val highPages = requests.filter { it.priority == PrefetchPriority.HIGH }.map { it.pageId }

        // Normal static ahead reaches 3000 (Page 3).
        // With 2000px extra ahead, the window reaches 2000 + 1000 + 2000 = 5000px, reaching Page 4 and Page 5!
        assertTrue(PageId(4L) in highPages)
        assertTrue(PageId(5L) in highPages)
    }

    @Test
    fun `predict expands lookahead upward during rapid upward fling`() {
        // Viewport at bottom: 5000..6000 (Page 6)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 5000f, 1000f, 1000f))
        // High velocity backwards: -4000 px/s * 0.5s = 2000px extra behind
        val backwardMotion = ViewportMotion(velocityY = -4000f)
        val requests = predictor.predict(scene, viewport, backwardMotion)

        val highPages = requests.filter { it.priority == PrefetchPriority.HIGH }.map { it.pageId }

        // Behind window expands from 5000 - 500 - 2000 = 2500 -> reaches Page 3 (2000..3000) and Page 4 (3000..4000)
        assertTrue(PageId(3L) in highPages)
        assertTrue(PageId(4L) in highPages)

        // Symmetrically expanded MEDIUM window extends another 1500px upward (down to 1000) reaching Page 2
        val mediumPages = requests.filter { it.priority == PrefetchPriority.MEDIUM }.map { it.pageId }
        assertTrue(PageId(2L) in mediumPages)
    }

    @Test
    fun `predictWindowIfChanged ignores pixel scroll inside same page range and motion class`() {
        val firstViewport = ReaderViewport(FloatRect.fromLtwh(0f, 1100f, 1000f, 700f))
        val secondViewport = ReaderViewport(FloatRect.fromLtwh(0f, 1200f, 1000f, 700f))

        val first = predictor.predictWindowIfChanged(scene, scene.resolve(firstViewport), ViewportMotion.Idle)
        val unchanged = predictor.predictWindowIfChanged(scene, scene.resolve(secondViewport), ViewportMotion.Idle)

        assertTrue(first != null)
        assertEquals(null, unchanged)
    }

    @Test
    fun `predictWindowIfChanged invalidates when velocity jumps across lookahead buckets`() {
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1100f, 1000f, 1000f))
        val frame = scene.resolve(viewport)

        // Moderate fling: 1500 px/s -> dynamicExtra = 750px -> lookaheadBucket = 750/1000 = 0
        val motion1 = ViewportMotion(velocityY = 1500f)
        val first = predictor.predictWindowIfChanged(scene, frame, motion1)
        assertTrue(first != null)

        // Same page, slightly faster velocity: 1600 px/s -> dynamicExtra = 800px -> lookaheadBucket = 0 -> suppressed
        val motion2 = ViewportMotion(velocityY = 1600f)
        val second = predictor.predictWindowIfChanged(scene, frame, motion2)
        assertEquals(null, second)

        // Violent fling: 8000 px/s -> dynamicExtra = 4000px -> lookaheadBucket = 4 -> triggers invalidation!
        val motion3 = ViewportMotion(velocityY = 8000f)
        val third = predictor.predictWindowIfChanged(scene, frame, motion3)
        assertTrue(third != null)
    }

    @Test
    fun `predictWindowIfChanged invalidates after scene geometry revision changes`() {
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1100f, 1000f, 700f))
        predictor.predictWindowIfChanged(scene, scene.resolve(viewport), ViewportMotion.Idle)

        scene.updatePageHint(PageId(7L), PageGeometryHint.Exact(1000, 1500))

        val updated = predictor.predictWindowIfChanged(scene, scene.resolve(viewport), ViewportMotion.Idle)
        assertTrue(updated != null)
    }
}
