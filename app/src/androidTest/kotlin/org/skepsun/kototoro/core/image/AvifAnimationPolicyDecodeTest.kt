package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.aomedia.avif.android.AvifDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises settings snapshots, the native decoder and frame reuse with the same two-frame file. */
@RunWith(AndroidJUnit4::class)
class AvifAnimationPolicyDecodeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun reductionFitsTheFrameBudgetAndPolicyChangesDoNotReuseSmallFrames() {
        var policy = AvifAnimationPolicy(2048, true)
        withLoader({ policy }) { decode ->
            val small = decode(32)
            assertTrue(small is AvifAnimatedDrawable)
            assertEquals(16, small.intrinsicWidth)
            release(small)

            policy = AvifAnimationPolicy(8192, true)
            val large = decode(32)
            assertNotSame(small, large)
            assertTrue(large is AvifAnimatedDrawable)
            assertEquals(32, large.intrinsicWidth)
            release(large)
        }
    }

    @Test
    fun disabledReductionShowsAnOriginalResolutionFirstFrameInsteadOfASmallAnimation() {
        withLoader({ AvifAnimationPolicy(4096, false) }) { decode ->
            val still = decode(8)
            assertFalse(still is AvifAnimatedDrawable)
            assertEquals(32, still.intrinsicWidth)
            assertEquals(32, still.intrinsicHeight)
        }
    }

    @Test
    fun disabledReductionKeepsAllOriginalFramesWhenTheyFit() {
        withLoader({ AvifAnimationPolicy(8192, false) }) { decode ->
            val animation = decode(8)
            assertTrue(animation is AvifAnimatedDrawable)
            assertEquals(32, animation.intrinsicWidth)
            release(animation)
        }
    }

    @Test
    fun twelveFramesPlayAtOriginalResolutionWithOnlyTwoFramesInTheBudget() {
        withLoader({ AvifAnimationPolicy(8192) }, "twelve-frame.avif") { decode ->
            val animation = decode(8) as AvifAnimatedDrawable
            try {
                assertEquals(32, animation.intrinsicWidth)
                assertEquals(8192L, animation.byteCount)
                assertAvifChanges(animation)
            } finally {
                release(animation)
            }
        }
    }

    @Test
    fun spareBudgetPreloadsMoreOriginalFramesWithinTheLimit() {
        withLoader({ AvifAnimationPolicy(6 * 4096L) }, "twelve-frame.avif") { decode ->
            val animation = decode(8) as AvifAnimatedDrawable
            try {
                assertEquals(32, animation.intrinsicWidth)
                assertEquals(6 * 4096L, animation.byteCount)
                assertAvifChanges(animation)
            } finally {
                release(animation)
            }
        }
    }

    @Test
    fun avifInsideCbzStillPlaysAfterTheVirtualFileSourceCloses() {
        withLoader({ AvifAnimationPolicy(8192) }, "twelve-frame.avif", archive = true) { decode ->
            val animation = decode(8) as AvifAnimatedDrawable
            try {
                assertEquals(32, animation.intrinsicWidth)
                assertEquals(8192L, animation.byteCount)
                assertAvifChanges(animation)
            } finally {
                release(animation)
            }
        }
    }

    @Test
    fun nativePlaybackReachesEveryFrameAndLoopsAfterTheCoilSourceCloses() {
        val input = instrumentation.context.assets.open("reader/twelve-frame.avif").use { it.readBytes() }
        val bytes = ByteBuffer.allocateDirect(input.size).apply { put(input); rewind() }
        val decoder = requireNotNull(AvifDecoder.create(bytes))
        try {
            assertEquals(12, decoder.frameCount)
            assertTrue("Expected infinite looping, got ${decoder.repetitionCount}", decoder.repetitionCount < 0)
            assertTrue("Unexpected durations: ${decoder.frameDurations.contentToString()}",
                decoder.frameDurations.all { it in 0.09..0.11 })
        } finally {
            decoder.release()
            keepAvifInputReachable(bytes)
        }
        withLoader({ AvifAnimationPolicy(8192) }, "twelve-frame.avif") { decode ->
            val animation = decode(8) as AvifAnimatedDrawable
            val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val delivered = CountDownLatch(14)
            val frameMasks = HashSet<Int>()
            val callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    who.draw(Canvas(target))
                    // The green square moves one pixel each frame in this lossy AVIF fixture.
                    var mask = 0
                    for (x in 0 until 32) {
                        val pixel = target.getPixel(x, 16)
                        if (Color.green(pixel) > Color.red(pixel) && Color.green(pixel) > Color.blue(pixel)) {
                            mask = mask or (1 shl x)
                        }
                    }
                    frameMasks.add(mask)
                    delivered.countDown()
                }

                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            }
            try {
                instrumentation.runOnMainSync {
                    animation.setBounds(0, 0, 32, 32)
                    animation.callback = callback
                    animation.start()
                }
                repeat(3) { System.gc() }
                val complete = delivered.await(5, TimeUnit.SECONDS)
                assertTrue("Native playback must cross the loop boundary: ${delivered.count} frames pending, " +
                    "${frameMasks.size} distinct frames, running=${animation.isRunning}",
                    complete)
                instrumentation.runOnMainSync {
                    animation.stop()
                    assertEquals(12, frameMasks.size)
                    assertEquals(8192L, animation.byteCount)
                }
            } finally {
                release(animation)
                keepAvifInputReachable(callback)
                target.recycle()
            }
        }
    }

    private fun withLoader(
        policyProvider: () -> AvifAnimationPolicy,
        fixtureName: String = "two-frame.avif",
        archive: Boolean = false,
        block: ((Int) -> Drawable) -> Unit,
    ) {
        val pool = AvifAnimationPool()
        val loader = ImageLoader.Builder(context)
            .memoryCache(null)
            .diskCache(null)
            .components {
                add(CbzFetcher.Factory())
                add(AvifImageDecoder.Factory(pool, policyProvider))
            }
            .build()
        val fixture = File(context.cacheDir, "policy-animated-image.avif").apply {
            instrumentation.context.assets.open("reader/$fixtureName").use { input ->
                if (archive) {
                    ZipOutputStream(outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("page.avif"))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                } else {
                    outputStream().use { input.copyTo(it) }
                }
            }
        }
        try {
            block { size ->
                val result = runBlocking {
                    val data = if (archive) Uri.parse("zip:${fixture.absolutePath}#page.avif") else fixture
                    loader.execute(ImageRequest.Builder(context).data(data).size(size, size).build())
                }
                assertTrue(result.toString(), result is SuccessResult)
                (result as SuccessResult).image.asDrawable(context.resources)
            }
        } finally {
            pool.clear()
            loader.shutdown()
        }
    }

    private fun release(drawable: Drawable) = instrumentation.runOnMainSync {
        (drawable as? AvifAnimatedDrawable)?.release()
    }
}
