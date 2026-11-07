package org.skepsun.kototoro.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.annotation.WorkerThread
import org.skepsun.kototoro.core.prefs.ReaderMode
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How the crop editor lays neighbouring pages next to the page: the order a reader meets them in. */
enum class StitchDirection {
    TOP_TO_BOTTOM,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    ;

    val isHorizontal: Boolean
        get() = this != TOP_TO_BOTTOM

    companion object {

        /** Follows the reader: strips stack vertically, paged and horizontal modes in reading order. */
        fun forReaderMode(mode: ReaderMode?, isContinuousHorizontalReversed: Boolean): StitchDirection = when (mode) {
            ReaderMode.STANDARD -> LEFT_TO_RIGHT
            ReaderMode.REVERSED -> RIGHT_TO_LEFT
            ReaderMode.CONTINUOUS_HORIZONTAL -> if (isContinuousHorizontalReversed) RIGHT_TO_LEFT else LEFT_TO_RIGHT
            ReaderMode.VERTICAL, ReaderMode.WEBTOON, null -> TOP_TO_BOTTOM
        }
    }
}

/**
 * Where each page lands in a stitched image. [extents] are the pages' lengths along the stitching axis,
 * in the order they are drawn; the other side is shared.
 */
internal data class StitchLayout(
    val width: Int,
    val height: Int,
    val extents: List<Int>,
)

/**
 * Lines up pages of [sizes] (width to height), each scaled to the cross side of the page at [baseIndex]
 * (its width when stacking, its height side by side), and shrinks the whole image only when it would
 * exceed [maxPixels] or [maxSide], so the pages keep their resolution whenever memory allows.
 */
internal fun stitchLayout(
    sizes: List<Pair<Int, Int>>,
    baseIndex: Int,
    horizontal: Boolean,
    maxPixels: Long,
    maxSide: Int = MAX_STITCHED_SIDE,
): StitchLayout {
    require(sizes.isNotEmpty()) { "Nothing to stitch" }
    val (baseWidth, baseHeight) = sizes[baseIndex]
    val cross = (if (horizontal) baseHeight else baseWidth).toDouble()
    require(cross > 0) { "Empty page" }
    val extents = sizes.map { (w, h) ->
        val along = if (horizontal) w * cross / h.coerceAtLeast(1) else h * cross / w.coerceAtLeast(1)
        along.coerceAtLeast(1.0)
    }
    val total = extents.sum()
    val scale = minOf(1.0, sqrt(maxPixels / (cross * total)), maxSide / total, maxSide / cross)
    val crossSide = (cross * scale).roundToInt().coerceAtLeast(1)
    val scaledExtents = extents.map { (it * scale).roundToInt().coerceAtLeast(1) }
    return if (horizontal) {
        StitchLayout(width = scaledExtents.sum(), height = crossSide, extents = scaledExtents)
    } else {
        StitchLayout(width = crossSide, height = scaledExtents.sum(), extents = scaledExtents)
    }
}

/**
 * Writes [files], given in reading order with the page itself at [baseIndex], into [output] lined up in
 * [direction] at the page's size, in [format] at [quality], and returns the stitched size. A strip saved
 * uncropped is this file as is, so a lossy [quality] should be the one the result is meant to have.
 */
@WorkerThread
internal fun stitchPages(
    files: List<File>,
    baseIndex: Int,
    direction: StitchDirection,
    output: File,
    format: Bitmap.CompressFormat,
    quality: Int = FULL_QUALITY,
): Pair<Int, Int> {
    val sizes = files.map { file ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Cannot decode ${file.name}")
        options.outWidth to options.outHeight
    }
    // Right to left, the first page read sits rightmost: draw the line reversed.
    val drawOrder = if (direction == StitchDirection.RIGHT_TO_LEFT) files.indices.reversed() else files.indices
    val layout = stitchLayout(
        sizes = drawOrder.map { sizes[it] },
        baseIndex = drawOrder.indexOf(baseIndex),
        horizontal = direction.isHorizontal,
        maxPixels = maxStitchedPixels(),
    )
    val result = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(result)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var offset = 0
        drawOrder.forEachIndexed { slot, fileIndex ->
            val extent = layout.extents[slot]
            val (srcWidth, srcHeight) = sizes[fileIndex]
            val options = BitmapFactory.Options().apply {
                inSampleSize = if (direction.isHorizontal) {
                    sampleSize(srcHeight, layout.height)
                } else {
                    sampleSize(srcWidth, layout.width)
                }
            }
            val file = files[fileIndex]
            val page = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw IOException("Cannot decode ${file.name}")
            try {
                val target = if (direction.isHorizontal) {
                    Rect(offset, 0, offset + extent, layout.height)
                } else {
                    Rect(0, offset, layout.width, offset + extent)
                }
                canvas.drawBitmap(page, null, target, paint)
            } finally {
                page.recycle()
            }
            offset += extent
        }
        output.outputStream().buffered().use { out ->
            if (!result.compress(format, quality, out)) throw IOException("Cannot encode stitched page")
        }
    } finally {
        result.recycle()
    }
    return layout.width to layout.height
}

/** The largest power of two that still decodes a [side] at least as long as [target]. */
private fun sampleSize(side: Int, target: Int): Int {
    var sample = 1
    while (side / (sample * 2) >= target) {
        sample *= 2
    }
    return sample
}

/** A quarter of the heap for the stitched bitmap, leaving room for the page decoded next to it. */
internal fun maxStitchedPixels(): Long =
    min(MAX_STITCHED_PIXELS, max(Runtime.getRuntime().maxMemory() / 4 / BYTES_PER_PIXEL, MIN_STITCHED_PIXELS))

private const val BYTES_PER_PIXEL = 4
private const val FULL_QUALITY = 100
private const val MAX_STITCHED_PIXELS = 40_000_000L
private const val MIN_STITCHED_PIXELS = 4_000_000L

/** JPEG cannot store a side longer than 65535. */
private const val MAX_STITCHED_SIDE = 65_000
