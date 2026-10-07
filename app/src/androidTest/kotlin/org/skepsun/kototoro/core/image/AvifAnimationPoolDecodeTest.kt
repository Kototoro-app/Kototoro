package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production Coil + libavif decoding through [AvifAnimationPool]: what every reader goes through. */
@RunWith(AndroidJUnit4::class)
class AvifAnimationPoolDecodeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun releasedAnimationIsReusedByTheNextDecodeOfThePage() = withLoader { pool, decode ->
        val first = decode(32)
        instrumentation.runOnMainSync { first.release() }

        val second = decode(32)
        assertSame("Showing the page again must not decode every frame again", first, second)
        assertPlays(second)

        instrumentation.runOnMainSync { second.release() }
        assertNotSame("Another decode size needs its own frames", second, decode(8).also(::releaseOnMain))
        pool.clear()
    }

    @Test
    fun aDrawableInUseIsNotHandedToASecondConsumer() = withLoader { pool, decode ->
        val first = decode(32)
        val second = decode(32)
        assertNotSame(first, second)
        releaseOnMain(first)
        releaseOnMain(second)
        pool.clear()
    }

    @Test
    fun clearedPoolFreesTheFrames() = withLoader { pool, decode ->
        val first = decode(32)
        releaseOnMain(first)
        pool.clear()

        val second = decode(32)
        assertNotSame(first, second)
        assertPlays(second)
        releaseOnMain(second)
        pool.clear()
    }

    private fun withLoader(block: (AvifAnimationPool, (Int) -> AvifAnimatedDrawable) -> Unit) {
        val pool = AvifAnimationPool()
        val loader = ImageLoader.Builder(context)
            .memoryCache(null)
            .diskCache(null)
            .components { add(AvifImageDecoder.Factory(pool)) }
            .build()
        val fixture = File(context.cacheDir, "pool-animated-image.avif").apply {
            instrumentation.context.assets.open("reader/two-frame.avif").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        try {
            block(pool) { size ->
                val result = runBlocking {
                    loader.execute(ImageRequest.Builder(context).data(fixture).size(size, size).build())
                }
                assertTrue(result.toString(), result is SuccessResult)
                (result as SuccessResult).image.asDrawable(context.resources) as AvifAnimatedDrawable
            }
        } finally {
            loader.shutdown()
        }
    }

    private fun releaseOnMain(drawable: AvifAnimatedDrawable) = instrumentation.runOnMainSync { drawable.release() }

    private fun assertPlays(drawable: AvifAnimatedDrawable) = instrumentation.runOnMainSync {
        val size = drawable.intrinsicWidth
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(target))
            val first = target.getPixel(size / 2, size / 2)
            drawable.start()
            drawable.run()
            drawable.draw(Canvas(target))
            assertNotEquals("A reused AVIF must still advance frames", first, target.getPixel(size / 2, size / 2))
        } finally {
            drawable.stop()
            target.recycle()
        }
    }
}
