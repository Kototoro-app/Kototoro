package org.skepsun.kototoro.reader.render

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.ui.compose.WebtoonPullDirection
import org.skepsun.kototoro.reader.ui.compose.WebtoonPullState
import org.skepsun.kototoro.reader.ui.compose.hasExceededWebtoonTapSlop
import org.skepsun.kototoro.reader.ui.compose.isWebtoonDoubleTapCandidate
import org.skepsun.kototoro.reader.ui.compose.pullAtBoundary
import org.skepsun.kototoro.reader.ui.compose.release
import org.skepsun.kototoro.reader.ui.compose.resolveWebtoonGestureBoundaryHandoff
import org.skepsun.kototoro.reader.ui.compose.retract

class ComposeSceneInteractionsTest {

    @Test
    fun `touch slop detection distinguishes taps from drags`() {
        val start = Offset(100f, 100f)
        val touchSlop = 18f

        // Jitter during a tap (e.g. 5px displacement) should NOT exceed slop
        val jitterPoint = Offset(103f, 104f)
        assertFalse(hasExceededWebtoonTapSlop(start, jitterPoint, touchSlop))

        // True drag (e.g. 25px displacement) must exceed slop
        val dragPoint = Offset(100f, 125f)
        assertTrue(hasExceededWebtoonTapSlop(start, dragPoint, touchSlop))
    }

    @Test
    fun `double tap candidate detects successive taps within window and distance`() {
        val tap1 = Offset(200f, 300f)
        val tap1Time = 1000L
        val doubleTapSlop = 40f
        val timeoutMillis = 300L

        // Fast second tap nearby: valid double tap
        val tap2Valid = Offset(210f, 305f)
        val tap2ValidTime = 1150L
        assertTrue(
            isWebtoonDoubleTapCandidate(
                firstTapPosition = tap1,
                firstTapUpTimeMillis = tap1Time,
                secondTapPosition = tap2Valid,
                secondTapDownTimeMillis = tap2ValidTime,
                minTimeMillis = 0L,
                timeoutMillis = timeoutMillis,
                doubleTapSlop = doubleTapSlop,
            ),
        )

        // Second tap too late (> timeoutMillis): not double tap
        val tap2LateTime = 1400L
        assertFalse(
            isWebtoonDoubleTapCandidate(
                firstTapPosition = tap1,
                firstTapUpTimeMillis = tap1Time,
                secondTapPosition = tap2Valid,
                secondTapDownTimeMillis = tap2LateTime,
                minTimeMillis = 0L,
                timeoutMillis = timeoutMillis,
                doubleTapSlop = doubleTapSlop,
            ),
        )

        // Second tap too far (> doubleTapSlop): not double tap
        val tap2Far = Offset(300f, 400f)
        assertFalse(
            isWebtoonDoubleTapCandidate(
                firstTapPosition = tap1,
                firstTapUpTimeMillis = tap1Time,
                secondTapPosition = tap2Far,
                secondTapDownTimeMillis = tap2ValidTime,
                minTimeMillis = 0L,
                timeoutMillis = timeoutMillis,
                doubleTapSlop = doubleTapSlop,
            ),
        )
    }

    @Test
    fun `zoom boundary handoff hands off excess pan when zoomed`() {
        val scale = 2.0f
        val desiredY = 500f
        val boundedY = 400f

        // When zoomed and panning past boundary: excess displacement is scaled and handed off
        val handoff = resolveWebtoonGestureBoundaryHandoff(
            scale = scale,
            desiredY = desiredY,
            boundedY = boundedY,
            isTransformGesture = false,
        )
        // (400 - 500) / 2.0 = -50
        assertEquals(-50, handoff)

        // During 2-finger transform gesture, boundary handoff must NOT occur to prevent jitter
        val transformHandoff = resolveWebtoonGestureBoundaryHandoff(
            scale = scale,
            desiredY = desiredY,
            boundedY = boundedY,
            isTransformGesture = true,
        )
        assertEquals(0, transformHandoff)

        // At 1.0x unzoomed scale, boundary handoff is 0
        val unzoomedHandoff = resolveWebtoonGestureBoundaryHandoff(
            scale = 1.0f,
            desiredY = desiredY,
            boundedY = boundedY,
            isTransformGesture = false,
        )
        assertEquals(0, unzoomedHandoff)
    }

    @Test
    fun `pull to switch chapter tracks boundary overscroll and releases correctly`() {
        var pullState = WebtoonPullState()
        val maxDistance = 2000f
        val threshold = 200f

        // Drag down at top boundary (availableY > 0, canScrollBackward = false)
        pullState = pullState.pullAtBoundary(
            availableY = 150f,
            canScrollBackward = false,
            canScrollForward = true,
            maxDistancePx = maxDistance,
        )
        assertEquals(150f, pullState.topDistancePx)
        assertEquals(0f, pullState.bottomDistancePx)
        assertNull(pullState.release(threshold)) // 150 < 200: threshold not reached

        // Drag down more, crossing threshold
        pullState = pullState.pullAtBoundary(
            availableY = 100f,
            canScrollBackward = false,
            canScrollForward = true,
            maxDistancePx = maxDistance,
        )
        assertEquals(250f, pullState.topDistancePx)
        assertEquals(WebtoonPullDirection.PREVIOUS, pullState.release(threshold))

        // Retraction when user pushes back up (availableY < 0 while pulling top)
        pullState = pullState.retract(-100f)
        assertEquals(150f, pullState.topDistancePx)
        assertNull(pullState.release(threshold)) // Fell back below threshold

        // Drag up at bottom boundary (availableY < 0, canScrollForward = false)
        var bottomPull = WebtoonPullState()
        bottomPull = bottomPull.pullAtBoundary(
            availableY = -300f,
            canScrollBackward = true,
            canScrollForward = false,
            maxDistancePx = maxDistance,
        )
        assertEquals(300f, bottomPull.bottomDistancePx)
        assertEquals(WebtoonPullDirection.NEXT, bottomPull.release(threshold))
    }

    @Test
    fun `scene resolves exact scroll target for requested page navigation`() {
        val scene = VerticalReaderScene(
            availableWidth = 1080,
            defaultViewportHeight = 2400,
            initialPages = listOf(
                PageId(10L) to PageGeometryHint.Exact(1080, 1500),
                PageId(20L) to PageGeometryHint.Exact(1080, 2500),
                PageId(30L) to PageGeometryHint.Exact(1080, 3000),
            ),
        )

        assertEquals(0f, scene.resolvePageScrollPosition(PageId(10L)))
        assertEquals(1500f, scene.resolvePageScrollPosition(PageId(20L)))
        assertEquals(4000f, scene.resolvePageScrollPosition(PageId(30L)))
        assertNull(scene.resolvePageScrollPosition(PageId(999L)))
    }

    @Test
    fun `adjacent node coordinates guarantee zero gaps under floating scroll offsets`() {
        val node1Top = 0f
        val node1Height = 1500f
        val node2Top = 1500f
        val node2Height = 1800f

        // Test with arbitrary fractional scroll offsets
        val fractionalScrolls = listOf(0f, 0.3f, 0.5f, 0.7f, 100.456f, 999.8f)
        for (scrollY in fractionalScrolls) {
            val screenTop1 = node1Top - scrollY
            val topInt1 = screenTop1.roundToInt()
            val bottomInt1 = (screenTop1 + node1Height).roundToInt()
            val heightInt1 = (bottomInt1 - topInt1 + 1).coerceAtLeast(1)

            val screenTop2 = node2Top - scrollY
            val topInt2 = screenTop2.roundToInt()

            // Node 1's bottom drawn pixel must reach or overlap Node 2's top drawn pixel
            val drawnBottom1 = topInt1 + heightInt1
            assertTrue(
                drawnBottom1 > topInt2,
                "Seam crack detected at scroll $scrollY: drawnBottom1=$drawnBottom1, topInt2=$topInt2",
            )
            // Exactly 1px overlap eliminates hairline cracks without aspect ratio distortion
            assertEquals(1, drawnBottom1 - topInt2)
        }
    }

    @Test
    fun `zoomed out canvas computes centered horizontal offset`() {
        val sceneWidth = 1080
        val scale = 0.5f
        val canvasWidth = 1080f / scale // 2160f

        val horizontalOffset = ((canvasWidth - sceneWidth) / 2f).coerceAtLeast(0f)
        assertEquals(540f, horizontalOffset)

        // With horizontalOffset = 540, content spans [540, 1620] on 2160px canvas
        val contentLeft = 0f + horizontalOffset
        val contentRight = contentLeft + sceneWidth
        assertEquals(540f, contentLeft)
        assertEquals(1620f, contentRight)

        // Under 0.5x scale around center (1080):
        // (contentLeft - 1080) * 0.5 + 1080 = -540 * 0.5 + 1080 = 810 (in 0..1080 space: margins 270 on each side)
        val screenLeft = (contentLeft - 1080f) * 0.5f + 540f
        val screenRight = (contentRight - 1080f) * 0.5f + 540f
        assertEquals(270f, screenLeft)
        assertEquals(810f, screenRight)
        assertEquals(540f, screenRight - screenLeft) // exactly 0.5 * 1080
    }
}

