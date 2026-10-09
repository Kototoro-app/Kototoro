package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals

/** Waits on the test thread so native playback can finish and the main looper can deliver frames. */
internal fun assertAvifChanges(drawable: AvifAnimatedDrawable) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    var first = 0
    var current = 0
    try {
        instrumentation.runOnMainSync {
            drawable.setBounds(0, 0, 32, 32)
            drawable.draw(Canvas(target))
            first = target.getPixel(16, 16)
            drawable.start()
            drawable.run() // Synthetic fixtures can advance immediately; native fixtures may still be decoding.
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.runOnMainSync {
                drawable.run() // Test fixtures can intentionally have minute-long frame delays.
                drawable.draw(Canvas(target))
                current = target.getPixel(16, 16)
            }
            if (current != first) break
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        assertNotEquals("AVIF playback must advance to different pixels", first, current)
    } finally {
        instrumentation.runOnMainSync { drawable.stop() }
        target.recycle()
    }
}
