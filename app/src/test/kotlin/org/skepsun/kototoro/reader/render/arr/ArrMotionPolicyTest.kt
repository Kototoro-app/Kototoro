package org.skepsun.kototoro.reader.render.arr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.ViewportMotion

class ArrMotionPolicyTest {

    @Test
    fun `dragging always requests HIGH refresh rate`() {
        val motion = ViewportMotion(
            velocityX = 0f,
            velocityY = 10f,
            isDragging = true,
            timestampNanos = 1000L,
        )
        assertEquals(RefreshRatePreference.HIGH, ArrMotionPolicy.resolvePreference(motion))
    }

    @Test
    fun `high speed fling requests HIGH refresh rate`() {
        val motion = ViewportMotion(
            velocityX = 0f,
            velocityY = 800f,
            isDragging = false,
            timestampNanos = 1000L,
        )
        assertEquals(RefreshRatePreference.HIGH, ArrMotionPolicy.resolvePreference(motion))
    }

    @Test
    fun `low speed scrolling requests NORMAL refresh rate`() {
        val motion = ViewportMotion(
            velocityX = 0f,
            velocityY = 150f,
            isDragging = false,
            timestampNanos = 1000L,
        )
        assertEquals(RefreshRatePreference.NORMAL, ArrMotionPolicy.resolvePreference(motion))
    }

    @Test
    fun `idle motion requests LOW refresh rate to save power`() {
        assertEquals(RefreshRatePreference.LOW, ArrMotionPolicy.resolvePreference(ViewportMotion.Idle))
    }
}
