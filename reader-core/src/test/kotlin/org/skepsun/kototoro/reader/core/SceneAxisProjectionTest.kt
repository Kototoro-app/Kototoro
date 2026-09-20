package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SceneAxisProjectionTest {

    @Test
    fun `forwardVelocity correctly projects physical velocity for all reading directions`() {
        // Vertical TOP_TO_BOTTOM: positive Y is forward reading
        val downMotion = ViewportMotion(isDragging = false, velocityY = 500f, velocityX = 0f)
        assertEquals(500f, SceneAxisProjection.forwardVelocity(downMotion, SceneReadingDirection.TOP_TO_BOTTOM))

        val upMotion = ViewportMotion(isDragging = false, velocityY = -500f, velocityX = 0f)
        assertEquals(-500f, SceneAxisProjection.forwardVelocity(upMotion, SceneReadingDirection.TOP_TO_BOTTOM))

        // Horizontal LEFT_TO_RIGHT: positive X is forward reading
        val rightMotion = ViewportMotion(isDragging = false, velocityY = 0f, velocityX = 400f)
        assertEquals(400f, SceneAxisProjection.forwardVelocity(rightMotion, SceneReadingDirection.LEFT_TO_RIGHT))

        val leftMotion = ViewportMotion(isDragging = false, velocityY = 0f, velocityX = -400f)
        assertEquals(-400f, SceneAxisProjection.forwardVelocity(leftMotion, SceneReadingDirection.LEFT_TO_RIGHT))

        // Horizontal RIGHT_TO_LEFT (Manga): negative X is forward reading
        assertEquals(-400f, SceneAxisProjection.forwardVelocity(rightMotion, SceneReadingDirection.RIGHT_TO_LEFT))
        assertEquals(400f, SceneAxisProjection.forwardVelocity(leftMotion, SceneReadingDirection.RIGHT_TO_LEFT))
    }

    @Test
    fun `expand expands bounds in correct reading direction`() {
        val baseViewport = ReaderViewport(FloatRect.fromLtwh(1000f, 2000f, 800f, 1200f))

        // Vertical: ahead is +Y (bottom), behind is -Y (top)
        val vExpanded = SceneAxisProjection.expand(
            viewport = baseViewport,
            direction = SceneReadingDirection.TOP_TO_BOTTOM,
            aheadPx = 600f,
            behindPx = 300f,
        )
        assertEquals(1000f, vExpanded.bounds.left)
        assertEquals(1700f, vExpanded.bounds.top) // 2000 - 300
        assertEquals(800f, vExpanded.bounds.width)
        assertEquals(2100f, vExpanded.bounds.height) // 1200 + 300 + 600

        // LTR: ahead is +X (right), behind is -X (left)
        val ltrExpanded = SceneAxisProjection.expand(
            viewport = baseViewport,
            direction = SceneReadingDirection.LEFT_TO_RIGHT,
            aheadPx = 400f,
            behindPx = 200f,
        )
        assertEquals(800f, ltrExpanded.bounds.left) // 1000 - 200
        assertEquals(2000f, ltrExpanded.bounds.top)
        assertEquals(1400f, ltrExpanded.bounds.width) // 800 + 200 + 400
        assertEquals(1200f, ltrExpanded.bounds.height)

        // RTL: ahead is -X (left), behind is +X (right)
        val rtlExpanded = SceneAxisProjection.expand(
            viewport = baseViewport,
            direction = SceneReadingDirection.RIGHT_TO_LEFT,
            aheadPx = 400f,
            behindPx = 200f,
        )
        assertEquals(600f, rtlExpanded.bounds.left) // 1000 - 400 (ahead is -X)
        assertEquals(2000f, rtlExpanded.bounds.top)
        assertEquals(1400f, rtlExpanded.bounds.width) // 800 + 400 + 200
        assertEquals(1200f, rtlExpanded.bounds.height)
    }

    @Test
    fun `primaryDimension returns height for vertical and width for horizontal`() {
        val rect = FloatRect.fromLtwh(0f, 0f, 800f, 1200f)
        assertEquals(1200f, SceneAxisProjection.primaryDimension(rect, SceneReadingDirection.TOP_TO_BOTTOM))
        assertEquals(800f, SceneAxisProjection.primaryDimension(rect, SceneReadingDirection.LEFT_TO_RIGHT))
        assertEquals(800f, SceneAxisProjection.primaryDimension(rect, SceneReadingDirection.RIGHT_TO_LEFT))
    }
}
