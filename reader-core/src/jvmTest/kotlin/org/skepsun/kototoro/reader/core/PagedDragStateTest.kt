package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagedDragStateTest {
    @Test
    fun `fractional content pan does not leak rounding error into page movement`() {
        val drag = PagedDragState(SceneReadingDirection.LEFT_TO_RIGHT)
        drag.dragBy(-0.1f, 200f, -200f..200f)
        assertEquals(0f, drag.pageOffset)
        assertEquals(2, drag.resolveTargetSlot(2, 1200f, 4000f, 5))
    }

    @Test
    fun `fast content pan cannot fling to another page`() {
        val drag = PagedDragState(SceneReadingDirection.LEFT_TO_RIGHT)

        val result = drag.dragBy(-100f, 200f, -200f..200f)

        assertEquals(100f, result.contentOffset)
        assertEquals(0f, drag.pageOffset)
        assertEquals(2, drag.resolveTargetSlot(2, 1200f, 4000f, 5))
    }

    @Test
    fun `only overflow residual turns the page in every reading direction`() {
        for (direction in SceneReadingDirection.entries) {
            val sign = if (direction == SceneReadingDirection.RIGHT_TO_LEFT) 1f else -1f
            val drag = PagedDragState(direction)
            val result = drag.dragBy(sign * 500f, -sign * 200f, -200f..200f)

            assertEquals(sign * 200f, result.contentOffset)
            assertEquals(100f, result.pageDelta)
            assertEquals(100f, drag.pageOffset)
            assertTrue(result.resetVelocity)
            assertEquals(2, drag.resolveTargetSlot(2, 1200f, 0f, 5))
        }
    }

    @Test
    fun `reverse drag unwinds page before returning to content and cannot fling`() {
        val drag = PagedDragState(SceneReadingDirection.LEFT_TO_RIGHT)
        drag.dragBy(-500f, 200f, -200f..200f)

        val unwind = drag.dragBy(60f, -200f, -200f..200f)
        assertEquals(-200f, unwind.contentOffset)
        assertEquals(40f, drag.pageOffset)

        val content = drag.dragBy(100f, unwind.contentOffset, -200f..200f)
        assertEquals(-140f, content.contentOffset)
        assertEquals(0f, drag.pageOffset)
        assertTrue(content.resetVelocity)
        assertEquals(2, drag.resolveTargetSlot(2, 1200f, -4000f, 5))
    }

    @Test
    fun `page drag supports displacement fling and boundary clamping`() {
        val drag = PagedDragState(SceneReadingDirection.LEFT_TO_RIGHT)
        drag.dragBy(-300f, 0f, 0f..0f)
        assertEquals(3, drag.resolveTargetSlot(2, 1200f, 0f, 5))
        assertEquals(4, drag.resolveTargetSlot(4, 1200f, 4000f, 5))

        drag.cancel()
        drag.dragBy(10f, 0f, 0f..0f)
        assertEquals(1, drag.resolveTargetSlot(2, 1200f, -4000f, 5))
        assertEquals(0, drag.resolveTargetSlot(0, 1200f, -4000f, 5))
    }

    @Test
    fun `multi touch cancellation clears page displacement and fling eligibility`() {
        val drag = PagedDragState(SceneReadingDirection.LEFT_TO_RIGHT)
        drag.dragBy(-500f, 0f, 0f..0f)
        drag.cancel()
        assertEquals(0f, drag.pageOffset)
        assertEquals(2, drag.resolveTargetSlot(2, 1200f, 4000f, 5))
    }

    @Test
    fun `invalid deltas do not poison subsequent navigation`() {
        val drag = PagedDragState(SceneReadingDirection.LEFT_TO_RIGHT)
        for (delta in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(100f, drag.dragBy(delta, 100f, -200f..200f).contentOffset)
        }
        assertEquals(0f, drag.pageOffset)
        drag.dragBy(-400f, 100f, -200f..200f)
        assertEquals(100f, drag.pageOffset)
    }
}
