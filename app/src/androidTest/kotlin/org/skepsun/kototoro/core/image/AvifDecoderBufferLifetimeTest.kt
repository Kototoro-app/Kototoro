package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aomedia.avif.android.AvifDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Pins the libavif behaviour [AvifImageDecoder] guards against (issue #564).
 *
 * libavif decodes later frames straight from the caller's encoded buffer instead of a copy, so that
 * buffer must outlive every nthFrame call. Here its memory is overwritten instead of collected: a GC
 * that frees it in a release build has the same effect, and the decoder then fails intermittently with
 * "Decoding of color planes failed". A debuggable test build keeps locals alive, so the GC itself
 * cannot be reproduced here. If this test starts failing, libavif copies its input and the
 * reachability fence in [AvifImageDecoder] is no longer needed.
 */
@RunWith(AndroidJUnit4::class)
class AvifDecoderBufferLifetimeTest {

    @Test
    fun laterFramesAreReadFromTheCallersBuffer() {
        val encoded = InstrumentationRegistry.getInstrumentation().context.assets
            .open("reader/two-frame.avif").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(encoded.size).put(encoded).rewind() as ByteBuffer
        val decoder = AvifDecoder.create(buffer)
        assertNotNull(decoder)
        decoder!!
        val bitmap = Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(2, decoder.frameCount)
            assertEquals(0, decoder.nthFrame(0, bitmap))

            // What a reused allocation looks like to the decoder.
            for (i in 0 until buffer.capacity()) buffer.put(i, 0)

            assertNotEquals(
                "libavif no longer reads frames from the caller's buffer",
                0,
                decoder.nthFrame(1, bitmap),
            )
        } finally {
            decoder.release()
            bitmap.recycle()
        }
    }
}
