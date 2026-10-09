package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises ownership when background native work outlives visibility or a reader's resource window. */
@RunWith(AndroidJUnit4::class)
class AvifStreamingLifecycleTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun budgetedReadAheadAbsorbsAnIsolatedSlowDecodeWithoutSkippingFrames() {
        val frames = BurstyFrames()
        val drawable = AvifAnimatedDrawable(frames, LongArray(12) { 30 }, -1)
        val timestamps = ArrayList<Long>()
        val indices = ArrayList<Int>()
        val delivered = CountDownLatch(24)
        val target = bitmap(Color.TRANSPARENT)
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                timestamps.add(SystemClock.uptimeMillis())
                who.draw(Canvas(target))
                indices.add(Color.red(target.getPixel(16, 16)))
                delivered.countDown()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        try {
            instrumentation.runOnMainSync {
                drawable.setBounds(0, 0, 32, 32)
                drawable.callback = callback
                drawable.start()
            }
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                drawable.stop()
                assertEquals((1..24).map { it % 12 }, indices.take(24))
                val intervals = timestamps.take(24).zipWithNext { a, b -> b - a }
                assertTrue("A budgeted queue must absorb isolated 80 ms decodes: $intervals", intervals.max() < 60)
            }
        } finally {
            instrumentation.runOnMainSync { drawable.release() }
            keepAvifInputReachable(callback)
            target.recycle()
        }
    }

    @Test
    fun finiteReadAheadNeverDecodesPastItsFinalFrame() {
        val frames = BurstyFrames()
        val drawable = AvifAnimatedDrawable(frames, LongArray(12) { 30 }, 0)
        try {
            instrumentation.runOnMainSync { drawable.start() }
            eventually { !drawable.isRunning }
            assertEquals((1..11).toList(), frames.decodedIndices)
        } finally {
            instrumentation.runOnMainSync { drawable.release() }
        }
    }

    @Test
    fun redrawWorkDoesNotAccumulateIntoTheAnimationFrameInterval() {
        val drawable = AvifAnimatedDrawable(listOf(Color.RED, Color.BLUE).map(::bitmap), longArrayOf(100, 100), -1)
        val timestamps = ArrayList<Long>()
        val delivered = CountDownLatch(6)
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                timestamps.add(SystemClock.uptimeMillis())
                // Model main-thread work while handling the previous frame's invalidation.
                SystemClock.sleep(40)
                delivered.countDown()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        try {
            instrumentation.runOnMainSync {
                drawable.callback = callback
                drawable.start()
            }
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                drawable.stop()
                val intervals = timestamps.zipWithNext { a, b -> b - a }
                assertTrue("100 ms frames accumulated redraw work: $intervals", intervals.average() < 120)
            }
        } finally {
            instrumentation.runOnMainSync { drawable.release() }
            keepAvifInputReachable(callback)
        }
    }

    @Test
    fun releaseWaitsForNativeDecodeWithoutBlockingTheMainThread() {
        val frames = BlockingFrames()
        val drawable = animation(frames)
        try {
            instrumentation.runOnMainSync { drawable.start() }
            await(frames.entered)
            instrumentation.runOnMainSync { drawable.release() }
            assertEquals("Native decode must still own its buffers", 1L, frames.closed.count)
            assertFalse(frames.firstFrame.isRecycled)
            assertFalse(drawable.isRunning)
            frames.allow.countDown()
            await(frames.closed)
            assertFalse(frames.closedWhileDecoding.get())
            assertTrue(frames.firstFrame.isRecycled)
            assertFalse(frames.decodedOnMain.get())
        } finally {
            frames.allow.countDown()
            instrumentation.runOnMainSync { drawable.release() }
        }
    }

    @Test
    fun stoppedAnimationKeepsOnePreparedFrameAndResumesWithoutOffscreenDecoding() {
        val frames = BlockingFrames()
        val drawable = animation(frames)
        try {
            instrumentation.runOnMainSync { drawable.start() }
            await(frames.entered)
            instrumentation.runOnMainSync { drawable.stop() }
            frames.allow.countDown()
            await(frames.finished)
            SystemClock.sleep(100)
            assertEquals(1, frames.calls.get())
            instrumentation.runOnMainSync {
                assertEquals(Color.RED, pixel(drawable))
                drawable.start()
            }
            eventually {
                var advanced = false
                instrumentation.runOnMainSync { advanced = pixel(drawable) == Color.BLUE }
                advanced
            }
            assertFalse(frames.decodedOnMain.get())
        } finally {
            frames.allow.countDown()
            instrumentation.runOnMainSync { drawable.release() }
            await(frames.closed)
        }
    }

    @Test
    fun laterFrameFailureKeepsTheLastFrameAndReleasesTheDecoder() {
        val frames = BlockingFrames(fail = true).also { it.allow.countDown() }
        val pool = AvifAnimationPool()
        val key = AvifAnimationPool.Key("failure", 1, 1, "32")
        val drawable = AvifAnimatedDrawable(frames, longArrayOf(10, 10), -1) {
            pool.park(key, it, false)
        }
        try {
            instrumentation.runOnMainSync { drawable.start() }
            await(frames.finished)
            eventually { !drawable.isRunning }
            instrumentation.runOnMainSync {
                assertEquals(Color.RED, pixel(drawable))
                drawable.release()
            }
            await(frames.closed)
            assertEquals(null, pool.take(key))
        } finally {
            instrumentation.runOnMainSync { drawable.release() }
            pool.clear()
        }
    }

    @Test
    fun backgroundDecodeConcurrencyIsLimitedAcrossAnimations() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val entered = CountDownLatch(2)
        val frames = List(6) { BlockingFrames(active = active, peak = peak, globalEntered = entered) }
        val drawables = frames.map(::animation)
        try {
            instrumentation.runOnMainSync { drawables.forEach { it.start() } }
            await(entered)
            SystemClock.sleep(100)
            assertEquals(2, active.get())
            assertEquals(2, peak.get())
        } finally {
            instrumentation.runOnMainSync { drawables.forEach { it.release() } }
            frames.forEach { it.allow.countDown() }
            frames.forEach { await(it.closed) }
        }
    }

    @Test
    fun libavifRepetitionCountCountsPlaysAfterTheFirst() {
        instrumentation.runOnMainSync {
            for (repetitions in 0..1) {
                val drawable = AvifAnimatedDrawable(
                    listOf(Color.RED, Color.BLUE).map(::bitmap),
                    longArrayOf(60_000, 60_000),
                    repetitions,
                )
                try {
                    drawable.start()
                    repeat(repetitions + 1) { play ->
                        if (play > 0) {
                            drawable.run()
                            assertEquals(Color.RED, pixel(drawable))
                        }
                        drawable.run()
                        assertEquals(Color.BLUE, pixel(drawable))
                        assertTrue(drawable.isRunning)
                    }
                    drawable.run()
                    assertFalse(drawable.isRunning)
                    assertEquals(Color.BLUE, pixel(drawable))
                } finally {
                    drawable.release()
                }
            }
        }
    }

    @Test
    fun completedFiniteAnimationIsFreedInsteadOfReusedAtItsLastFrame() {
        instrumentation.runOnMainSync {
            val buffers = listOf(Color.RED, Color.BLUE).map(::bitmap)
            var offeredToPool = false
            val drawable = AvifAnimatedDrawable(buffers, longArrayOf(60_000, 60_000), 0) {
                offeredToPool = true
                true
            }
            drawable.start()
            drawable.run()
            drawable.run()
            drawable.release()
            assertFalse(offeredToPool)
            assertTrue(buffers.all { it.isRecycled })
        }
    }

    private fun animation(frames: BlockingFrames) = AvifAnimatedDrawable(frames, longArrayOf(10, 10), -1)

    private fun pixel(drawable: AvifAnimatedDrawable): Int {
        val target = bitmap(Color.TRANSPARENT)
        return try {
            drawable.setBounds(0, 0, 32, 32)
            drawable.draw(Canvas(target))
            target.getPixel(16, 16)
        } finally {
            target.recycle()
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for background decode or cleanup", latch.await(5, TimeUnit.SECONDS))
    }

    private fun eventually(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!predicate()) {
            assertTrue("Timed out waiting for frame delivery", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20)
        }
    }

    /** Ignores interrupts like a native call, until the test explicitly allows it to finish. */
    private class BlockingFrames(
        private val fail: Boolean = false,
        private val active: AtomicInteger = AtomicInteger(),
        private val peak: AtomicInteger = AtomicInteger(),
        private val globalEntered: CountDownLatch? = null,
    ) : AvifAnimationFrames {
        private val buffers = listOf(bitmap(Color.RED), bitmap(Color.BLUE))
        override val firstFrame = buffers.first()
        override val byteCount = 8192L
        override val decodeInBackground = true
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val calls = AtomicInteger()
        val decodedOnMain = AtomicBoolean()
        val closedWhileDecoding = AtomicBoolean()
        private val decoding = AtomicBoolean()

        override fun decode(index: Int, displayed: Bitmap): Bitmap {
            decoding.set(true)
            calls.incrementAndGet()
            decodedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            entered.countDown()
            globalEntered?.countDown()
            try {
                while (allow.count != 0L) {
                    try {
                        allow.await()
                    } catch (_: InterruptedException) {
                        // JNI decoders are not guaranteed to observe a coroutine's thread interruption.
                    }
                }
                if (fail) throw IllegalStateException("Broken later frame")
                return buffers.first { it !== displayed }.apply {
                    eraseColor(if (index == 0) Color.RED else Color.BLUE)
                }
            } finally {
                decoding.set(false)
                active.decrementAndGet()
                finished.countDown()
            }
        }

        override fun close() {
            closedWhileDecoding.set(decoding.get())
            buffers.forEach { it.recycle() }
            closed.countDown()
        }
    }

    private class BurstyFrames : AvifAnimationFrames {
        private val buffers = List(6) { bitmap(Color.BLACK) }
        override val firstFrame = buffers.first()
        override val byteCount = buffers.sumOf { it.allocationByteCount.toLong() }
        override val bufferCount = buffers.size
        override val decodeInBackground = true
        val decodedIndices = ArrayList<Int>()
        private var position = 0

        override fun decode(index: Int, displayed: Bitmap): Bitmap {
            SystemClock.sleep(if (index % 6 == 0) 80 else 2)
            decodedIndices.add(index)
            position = (position + 1) % buffers.size
            return buffers[position].apply { eraseColor(Color.rgb(index, 0, 0)) }
        }

        override fun close() = buffers.forEach { it.recycle() }
    }

    private companion object {
        fun bitmap(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    }
}
