package org.skepsun.kototoro.ios.shims

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.icu.text.Collator
import android.icu.text.Normalizer2
import android.icu.text.StringSearch
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import com.squareup.zstd.okio.zstdCompress
import com.squareup.zstd.okio.zstdDecompress
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okio.Buffer
import okio.buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringReader
import java.text.StringCharacterIterator
import java.util.Locale

class PlatformShimsTest {
    @Test fun `WebView base exposes display resources and dispatches extension generated touches`() {
        val resources = android.content.res.Resources.getSystem()
        val context = object : android.content.ContextWrapper(null) {
            override fun getResources() = resources
        }
        var received: android.view.MotionEvent? = null
        val view = object : android.widget.AbsoluteLayout(context) {
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                received = event
                return true
            }
        }
        assertSame(resources, view.resources)
        assertTrue(view.resources.displayMetrics.density > 0f)
        val coordinates = android.view.MotionEvent.PointerCoords().apply { x = 120f; y = 240f }
        val event = android.view.MotionEvent.obtain(1L, 2L, 1, 1,
            arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = 1 }),
            arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, 4098, 0)
        coordinates.x = 999f
        assertEquals(120f, event.x)
        assertTrue(view.dispatchTouchEvent(event))
        assertSame(event, received)
        event.recycle()
    }

    @Test fun `cropping copies pixels and preserves immutable ownership`() {
        val original = Bitmap.createBitmap(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(original, 1, 0, 2, 2)
        assertEquals(2, crop.getPixel(0, 0))
        assertEquals(6, crop.getPixel(1, 1))
        assertFalse(crop.isMutable)
        assertThrows(IllegalStateException::class.java) { crop.setPixel(0, 0, 8) }
        original.recycle()
        assertEquals(2, crop.getPixel(0, 0))
    }

    @Test fun `negative stride and region checks follow Android pixel semantics`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(intArrayOf(1, 2, 3, 4), 2, -2, 0, 0, 2, 2)
        assertEquals(3, bitmap.getPixel(0, 0))
        assertEquals(2, bitmap.getPixel(1, 1))
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { bitmap.getPixels(IntArray(3), 0, 2, 0, 0, 2, 2) }
        assertThrows(IllegalArgumentException::class.java) { Bitmap.createBitmap(bitmap, 1, 0, 2, 2) }
        bitmap.recycle()
        assertThrows(IllegalStateException::class.java) { bitmap.getPixel(0, 0) }
    }

    @Test fun `canvas scales clips blends alpha and restores transforms`() {
        val source = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 2, 1, Bitmap.Config.ARGB_8888)
        val target = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        target.eraseColor(0xff0000ff.toInt())
        val canvas = Canvas(target)
        canvas.drawBitmap(source, null, Rect(-2, 0, 2, 1), null)
        assertEquals(0xff00ff00.toInt(), target.getPixel(0, 0))
        assertEquals(0xff0000ff.toInt(), target.getPixel(2, 0))
        canvas.drawColor(0x80ffffff.toInt())
        assertEquals(0xff8080ff.toInt(), target.getPixel(2, 0))
        val save = canvas.save()
        canvas.translate(2f, 1f)
        canvas.drawBitmap(source, 0f, 0f, null)
        canvas.restoreToCount(save)
        assertEquals(1, canvas.saveCount)
        assertEquals(0xffff0000.toInt(), target.getPixel(2, 1))
    }

    @Test fun `streaming JSON traverses nested values and keeps 64 bit integers exact`() {
        JsonReader(StringReader("""{"skip":[{},[1,true,null]],"id":9223372036854775807,"text":"\u4e2d\n"}""")).use {
            it.beginObject()
            assertEquals("skip", it.nextName())
            it.skipValue()
            assertEquals("id", it.nextName())
            assertEquals(Long.MAX_VALUE, it.nextLong())
            assertEquals("text", it.nextName())
            assertEquals("中\n", it.nextString())
            assertFalse(it.hasNext())
            it.endObject()
            assertEquals(JsonToken.END_DOCUMENT, it.peek())
        }
    }

    @Test fun `canvas rotation moves both pixel axes and preserves saved state`() {
        val source = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 2, 1, Bitmap.Config.ARGB_8888)
        val target = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val saved = canvas.save()
        canvas.translate(1f, 0f)
        canvas.rotate(90f)
        canvas.drawBitmap(source, 0f, 0f, null)
        assertEquals(0xffff0000.toInt(), target.getPixel(0, 0))
        assertEquals(0xff00ff00.toInt(), target.getPixel(0, 1))
        canvas.restoreToCount(saved)
        canvas.drawBitmap(source, 0f, 0f, null)
        assertEquals(0xff00ff00.toInt(), target.getPixel(1, 0))
    }

    @Test fun `JSON rejects malformed separators trailing data invalid escapes and fractional integers`() {
        for (input in listOf("[1 2]", "[1,]", "[01]", "[\"\\x\"]", "{\"a\" 1}", "[1] []")) {
            assertThrows(IOException::class.java, { JsonReader(StringReader(input)).use { it.skipValue(); it.peek() } }, input)
        }
        val reader = JsonReader(StringReader("[1.2,3]"))
        reader.beginArray()
        assertThrows(NumberFormatException::class.java) { reader.nextLong() }
        assertEquals(1.2, reader.nextDouble())
        assertEquals(3, reader.nextInt())
    }

    @Test fun `JSON lenient mode permits comments and unquoted names`() {
        JsonReader(StringReader("{/*comment*/ unquoted:'value'}")).use {
            it.isLenient = true
            it.beginObject()
            assertEquals("unquoted", it.nextName())
            assertEquals("value", it.nextString())
            it.endObject()
        }
    }

    @Test fun `Unicode normalization and accent insensitive collation retain original match offsets`() {
        assertEquals("é", Normalizer2.getNFCInstance().normalize("e\u0301"))
        assertEquals("strasse σσ i\u0307 ı", Normalizer2.getNFKCCasefoldInstance().normalize("ＳＴＲＡＳＳＥ Σς İ ı"))
        assertEquals("abc", Normalizer2.getNFKCCasefoldInstance().normalize("A\u200DB\uFEFFC"))
        val collator = Collator.getInstance(Locale.FRENCH) as android.icu.text.RuleBasedCollator
        collator.strength = Collator.PRIMARY
        val search = StringSearch("cafe", StringCharacterIterator("a café cafe"), collator)
        assertEquals(2, search.first())
        assertEquals("café", search.matchedText)
        assertEquals(7, search.next())
        assertEquals(-1, search.next())
        assertEquals(7, search.last())
        collator.freeze()
        assertThrows(UnsupportedOperationException::class.java) { collator.strength = Collator.SECONDARY }
    }

    @Test fun `pure Java zstd round trip releases streams`() {
        val content = "测试图源压缩响应".repeat(1000)
        val compressed = Buffer()
        zstdCompress(compressed).buffer().use { it.writeUtf8(content) }
        assertTrue(compressed.size > 0)
        assertEquals(content, zstdDecompress(compressed).buffer().use { it.readUtf8() })
    }

    @Test fun `UA uses request default and forced values in order`() {
        val interceptor = UserAgentInterceptor { "default" }
        assertEquals("default", interceptor.effectiveUserAgent(null))
        assertEquals("extension", interceptor.effectiveUserAgent("extension"))
        interceptor.forceUserAgent("host")
        assertEquals("host", interceptor.effectiveUserAgent("extension"))
        interceptor.clearForcedUserAgent()
        assertEquals("extension", interceptor.effectiveUserAgent("extension"))
    }

    @Test fun `clock preserves interrupt status and remains monotonic`() {
        val before = SystemClock.elapsedRealtimeNanos()
        Thread.currentThread().interrupt()
        try { SystemClock.sleep(2); assertTrue(Thread.currentThread().isInterrupted) }
        finally { Thread.interrupted() }
        assertTrue(SystemClock.elapsedRealtimeNanos() >= before)
        assertTrue(SystemClock.uptimeClock().millis() > 0)
    }
}
