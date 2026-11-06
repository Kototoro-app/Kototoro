package org.skepsun.kototoro.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where each stacked page lands in a stitched image: one shared [width] and a height per page. */
internal data class StitchLayout(
    val width: Int,
    val heights: List<Int>,
) {
    val height: Int
        get() = heights.sum()
}

/**
 * Stacks pages of [sizes] (width to height) top to bottom, each scaled to [baseWidth], and shrinks the
 * whole image only when it would exceed [maxPixels] or [maxSide], so the pages keep their resolution
 * whenever memory allows.
 */
internal fun stitchLayout(
    sizes: List<Pair<Int, Int>>,
    baseWidth: Int,
    maxPixels: Long,
    maxSide: Int = MAX_STITCHED_SIDE,
): StitchLayout {
    require(sizes.isNotEmpty() && baseWidth > 0) { "Nothing to stitch" }
    val heights = sizes.map { (w, h) -> (h.toDouble() * baseWidth / w.coerceAtLeast(1)).coerceAtLeast(1.0) }
    val totalHeight = heights.sum()
    val scale = minOf(
        1.0,
        sqrt(maxPixels / (baseWidth * totalHeight)),
        maxSide / totalHeight,
        maxSide.toDouble() / baseWidth,
    )
    return StitchLayout(
        width = (baseWidth * scale).roundToInt().coerceAtLeast(1),
        heights = heights.map { (it * scale).roundToInt().coerceAtLeast(1) },
    )
}

/**
 * Writes [files] stacked top to bottom into [output] at the width of the page at [baseIndex], in
 * [format] at full quality, and returns the stitched size.
 */
@WorkerThread
internal fun stitchPages(
    files: List<File>,
    baseIndex: Int,
    output: File,
    format: Bitmap.CompressFormat,
): Pair<Int, Int> {
    val sizes = files.map { file ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Cannot decode ${file.name}")
        options.outWidth to options.outHeight
    }
    val layout = stitchLayout(sizes, sizes[baseIndex].first, maxStitchedPixels())
    val result = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(result)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var top = 0
        files.forEachIndexed { i, file ->
            val height = layout.heights[i]
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(sizes[i].first, layout.width)
            }
            val page = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw IOException("Cannot decode ${file.name}")
            try {
                canvas.drawBitmap(page, null, Rect(0, top, layout.width, top + height), paint)
            } finally {
                page.recycle()
            }
            top += height
        }
        output.outputStream().buffered().use { out ->
            if (!result.compress(format, FULL_QUALITY, out)) throw IOException("Cannot encode stitched page")
        }
    } finally {
        result.recycle()
    }
    return layout.width to layout.height
}

/** The largest power of two that still decodes [width] at least as wide as [targetWidth]. */
private fun sampleSize(width: Int, targetWidth: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetWidth) {
        sample *= 2
    }
    return sample
}

/** A quarter of the heap for the stitched bitmap, leaving room for the page decoded next to it. */
private fun maxStitchedPixels(): Long =
    min(MAX_STITCHED_PIXELS, max(Runtime.getRuntime().maxMemory() / 4 / BYTES_PER_PIXEL, MIN_STITCHED_PIXELS))

private const val BYTES_PER_PIXEL = 4
private const val FULL_QUALITY = 100
private const val MAX_STITCHED_PIXELS = 40_000_000L
private const val MIN_STITCHED_PIXELS = 4_000_000L

/** JPEG cannot store a side longer than 65535. */
private const val MAX_STITCHED_SIDE = 65_000
