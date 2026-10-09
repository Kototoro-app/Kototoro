package org.skepsun.kototoro.core.image

import android.content.ComponentCallbacks2
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvifAnimationPoolTest {

    @Test
    fun `encoded input is included in the parked budget`() {
        val pool = AvifAnimationPool(budgetBytes = 100)
        val drawable = animation(bytes = 80)
        every { drawable.retainedByteCount } returns 120
        assertFalse(pool.park(key("a"), drawable, isSampled = false))
        assertNull(pool.take(key("a")))
    }

    @Test
    fun `lowering the live budget evicts cached frames before reuse`() {
        var limit = 100L
        val pool = AvifAnimationPool(budgetProvider = { limit })
        val drawable = animation(bytes = 80)
        assertTrue(pool.park(key("a"), drawable, isSampled = true))
        limit = 40

        assertNull(pool.take(key("a")))
        verify { drawable.recycleFrames() }
        assertFalse(pool.park(key("b"), animation(bytes = 80), isSampled = false))
    }

    @Test
    fun `a parked animation is handed back once for the same decode`() {
        val pool = AvifAnimationPool(budgetBytes = 100)
        val drawable = animation(bytes = 40)
        assertTrue(pool.park(key("a"), drawable, isSampled = true))

        assertNull(pool.take(key("a", request = "other size")))
        val taken = pool.take(key("a"))
        assertSame(drawable, taken?.drawable)
        assertTrue(taken!!.isSampled)
        verify { drawable.revive() }
        assertNull(pool.take(key("a")), "Frames have one owner at a time")
        verify(exactly = 0) { drawable.recycleFrames() }
    }

    @Test
    fun `a changed file does not get the old frames`() {
        val pool = AvifAnimationPool(budgetBytes = 100)
        pool.park(key("a", length = 10), animation(bytes = 40), isSampled = false)

        assertNull(pool.take(key("a", length = 11)))
    }

    @Test
    fun `the oldest animations are freed beyond the budget`() {
        val pool = AvifAnimationPool(budgetBytes = 100)
        val first = animation(bytes = 40)
        val second = animation(bytes = 40)
        val third = animation(bytes = 40)
        pool.park(key("1"), first, isSampled = false)
        pool.park(key("2"), second, isSampled = false)
        pool.take(key("1"))?.let { pool.park(key("1"), it.drawable, isSampled = false) }
        pool.park(key("3"), third, isSampled = false)

        verify { second.recycleFrames() }
        verify(exactly = 0) { first.recycleFrames() }
        verify(exactly = 0) { third.recycleFrames() }
        assertNull(pool.take(key("2")))
    }

    @Test
    fun `an animation larger than the budget is not kept`() {
        val pool = AvifAnimationPool(budgetBytes = 100)

        assertFalse(pool.park(key("a"), animation(bytes = 101), isSampled = false))
        assertNull(pool.take(key("a")))
    }

    @Test
    fun `memory pressure frees parked frames but leaving the ui does not`() {
        val pool = AvifAnimationPool(budgetBytes = 100)
        val drawable = animation(bytes = 40)
        pool.park(key("a"), drawable, isSampled = false)

        pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        verify(exactly = 0) { drawable.recycleFrames() }

        @Suppress("DEPRECATION")
        pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        verify { drawable.recycleFrames() }
        assertNull(pool.take(key("a")))
    }

    @Test
    fun `frames recycled while parked are not handed out`() {
        val pool = AvifAnimationPool(budgetBytes = 100)
        val drawable = animation(bytes = 40)
        every { drawable.isUsable() } returns false
        pool.park(key("a"), drawable, isSampled = false)

        assertNull(pool.take(key("a")))
    }

    private fun animation(bytes: Long) = mockk<AvifAnimatedDrawable>(relaxed = true).also {
        every { it.byteCount } returns bytes
        every { it.retainedByteCount } returns bytes
        every { it.isUsable() } returns true
    }

    private fun key(path: String, length: Long = 10, request: String = "size") =
        AvifAnimationPool.Key(path = path, length = length, lastModifiedMillis = 1, request = request)
}
