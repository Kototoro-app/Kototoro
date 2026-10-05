package org.skepsun.kototoro.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.SynchronizedSieveCache
import org.skepsun.kototoro.core.image.LocalImageRegionDecoder
import org.skepsun.kototoro.reader.core.ReaderEdgeDetection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EdgeDetector(private val context: Context) {

    private val mutex = Mutex()
    private val cache = SynchronizedSieveCache<Uri, Rect>(CACHE_SIZE)

    suspend fun getBounds(uri: Uri): Rect? {
        cache[uri]?.let { rect ->
            return rect
        }
        val bounds = mutex.withLock {
            withContext(Dispatchers.IO) {
                val decoder = runInterruptible {
                    LocalImageRegionDecoder.open(context.contentResolver, uri, Bitmap.Config.RGB_565)
                }
                decoder.use {
                    val size = decoder.size
                    val sampleSize = ReaderEdgeDetection.sampleSize(size.x, size.y)
                    val fullBitmap = decoder.decodeRegion(Rect(0, 0, size.x, size.y), sampleSize)
                    try {
                        // The scan itself is shared with the Windows reader (reader-core `ReaderEdgeDetection`).
                        ReaderEdgeDetection.contentBounds(size.x, size.y, sampleSize, fullBitmap.width, fullBitmap.height) {
                            out, x, y, width, height -> fullBitmap.getPixels(out, 0, width, x, y, width, height)
                        }?.let { decoder.toDisplayRect(Rect(it.left, it.top, it.right, it.bottom)) }
                    } finally {
                        fullBitmap.recycle()
                    }
                }
            }
        }
        if (bounds != null) {
            cache.put(uri, bounds)
        }
        return bounds
    }

    companion object {

        private const val CACHE_SIZE = 24
        fun isColorTheSame(@ColorInt a: Int, @ColorInt b: Int, tolerance: Int): Boolean =
            ReaderEdgeDetection.isColorTheSame(a, b, tolerance)
    }
}
