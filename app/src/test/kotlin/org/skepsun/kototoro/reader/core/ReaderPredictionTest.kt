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
    }
}
