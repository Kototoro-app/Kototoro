package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewportMotionTest {

    @Test
    fun `default instance is completely idle`() {
        val motion = ViewportMotion.Idle
        assertEquals(0f, motion.velocityX)
        assertEquals(0f, motion.velocityY)
        assertFalse(motion.isDragging)
        assertEquals(0f, motion.speed)
        assertTrue(motion.isIdle)
    }

    @Test
    fun `speed computes Euclidean norm of horizontal and vertical velocities`() {
        val motion = ViewportMotion(velocityX = 300f, velocityY = 400f, isDragging = false)
        assertEquals(500f, motion.speed, 0.001f)
        assertFalse(motion.isIdle)
    }

    @Test
    fun `isIdle is false when dragging even if speed is zero`() {
        val motion = ViewportMotion(velocityX = 0f, velocityY = 0f, isDragging = true)
        assertFalse(motion.isIdle)
    }
}
