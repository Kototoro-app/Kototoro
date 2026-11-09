package org.skepsun.kototoro.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.annotation.WorkerThread
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** A crop in source pixels, right and bottom exclusive. */
internal data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isWhole(width: Int, height: Int): Boolean = left == 0 && top == 0 && right == width && bottom == height
}

/**
 * Maps the crop frame onto the source file. The editor shows a downsampled copy of the image, laid out
 * at [imageLeft]..[imageBottom] on screen; the frame at [cropLeft]..[cropBottom] in the same coordinates
 * covers the same share of the source at [sourceWidth] x [sourceHeight]. Returns null for a frame that
 * misses the image.
 */
internal fun cropRegionInSource(
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float,
    imageLeft: Float,
    imageTop: Float,
    imageRight: Float,
    imageBottom: Float,
    sourceWidth: Int,
    sourceHeight: Int,
): PixelRegion? {
    val imageWidth = imageRight - imageLeft
    val imageHeight = imageBottom - imageTop
    if (imageWidth <= 0f || imageHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) return null
    val scaleX = sourceWidth / imageWidth
    val scaleY = sourceHeight / imageHeight
    // A frame within half a screen pixel of an image edge means that edge: the image is shown smaller
    // than it is, so rounding there would cut off a sliver of source pixels.
    fun edge(offset: Float, extent: Float, scale: Float, max: Int): Int = when {
        offset < EDGE_SNAP -> 0
        extent - offset < EDGE_SNAP -> max
        else -> (offset * scale).roundToInt().coerceIn(0, max)
    }
    val left = edge(cropLeft - imageLeft, imageWidth, scaleX, sourceWidth)
    val top = edge(cropTop - imageTop, imageHeight, scaleY, sourceHeight)
    val right = edge(cropRight - imageLeft, imageWidth, scaleX, sourceWidth)
    val bottom = edge(cropBottom - imageTop, imageHeight, scaleY, sourceHeight)
    if (right - left < 1 || bottom - top < 1) return null
    return PixelRegion(left, top, right, bottom)
}

/** Whether [shownWidth] x [shownHeight] is the source drawn upright, not turned by its EXIF orientation. */
internal fun isSameOrientation(shownWidth: Int, shownHeight: Int, sourceWidth: Int, sourceHeight: Int): Boolean {
    if (shownWidth <= 0 || shownHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return false
    val shown = shownWidth.toDouble() / shownHeight
    val source = sourceWidth.toDouble() / sourceHeight
    return abs(shown - source) / source < ORIENTATION_TOLERANCE
}

/**
 * Writes [region] of [source] into [output] at the source resolution, shrinking it only when the crop
 * would not fit in memory, and returns the written size. A crop of the whole image already in [format]
 * is copied as is, so saving an untouched page loses nothing.
 */
@WorkerThread
internal fun cropPageRegion(
    source: File,
    region: PixelRegion,
    sourceMimeType: String?,
    output: File,
    format: Bitmap.CompressFormat,
    quality: Int,
    maxPixels: Long = maxStitchedPixels(),
): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (region.isWhole(bounds.outWidth, bounds.outHeight) && format.matchesMimeType(sourceMimeType)) {
        source.copyTo(output, overwrite = true)
        return bounds.outWidth to bounds.outHeight
    }
    val decoder = newRegionDecoder(source) ?: throw IOException("Cannot decode ${source.name}")
    try {
        var sample = 1
        while ((region.width.toLong() / sample) * (region.height.toLong() / sample) > maxPixels) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom), options)
            ?: throw IOException("Cannot decode ${source.name}")
        try {
            output.outputStream().buffered().use { out ->
                if (!bitmap.compress(format, quality, out)) throw IOException("Cannot encode cropped page")
            }
            return bitmap.width to bitmap.height
        } finally {
            bitmap.recycle()
        }
    } finally {
        decoder.recycle()
    }
}

@Suppress("DEPRECATION")
private fun newRegionDecoder(file: File): BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    BitmapRegionDecoder.newInstance(file.absolutePath)
} else {
    BitmapRegionDecoder.newInstance(file.absolutePath, false)
}

@Suppress("DEPRECATION")
private fun Bitmap.CompressFormat.matchesMimeType(mimeType: String?): Boolean = when (mimeType) {
    "image/jpeg" -> this == Bitmap.CompressFormat.JPEG
    "image/png" -> this == Bitmap.CompressFormat.PNG
    "image/webp" -> this == Bitmap.CompressFormat.WEBP ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && this == Bitmap.CompressFormat.WEBP_LOSSY)
    else -> false
}

/**
 * The libjpeg quality a JPEG at [file] was most likely saved with, read from its luminance quantization
 * table, or null when it is not a JPEG or has no such table. Re-encoding a page at this quality keeps
 * it about as large as it came, where a fixed high quality can nearly double it.
 */
@WorkerThread
internal fun estimateJpegQuality(file: File): Int? = runCatching {
    DataInputStream(file.inputStream().buffered()).use { input -> readJpegLuminanceTable(input) }
}.getOrNull()?.let(::jpegQualityOf)

/** The libjpeg quality whose scaled standard table best matches [table], in any coefficient order. */
internal fun jpegQualityOf(table: IntArray): Int? {
    if (table.size != DCT_COEFFICIENTS) return null
    val scale = table.sum() * 100.0 / STANDARD_LUMINANCE_SUM
    if (scale <= 0.0) return null
    val quality = if (scale <= 100.0) (200.0 - scale) / 2.0 else 5000.0 / scale
    return floor(quality + 0.5).toInt().coerceIn(1, 100)
}

private fun readJpegLuminanceTable(input: DataInputStream): IntArray? {
    if (input.readUnsignedShort() != 0xFFD8) return null
    while (true) {
        var marker = input.readUnsignedByte()
        if (marker != 0xFF) return null
        do {
            marker = input.readUnsignedByte()
        } while (marker == 0xFF)
        // Standalone markers carry no length.
        if (marker == 0x01 || marker in 0xD0..0xD7) continue
        if (marker == 0xDA || marker == 0xD9) return null
        val length = input.readUnsignedShort() - 2
        if (length < 0) return null
        if (marker != 0xDB) {
            input.skipFully(length)
            continue
        }
        var remaining = length
        while (remaining > 0) {
            val info = input.readUnsignedByte()
            val wide = info shr 4 != 0
            val table = IntArray(DCT_COEFFICIENTS) {
                if (wide) input.readUnsignedShort() else input.readUnsignedByte()
            }
            remaining -= 1 + DCT_COEFFICIENTS * (if (wide) 2 else 1)
            if (info and 0x0F == 0) return table
        }
    }
}

private fun DataInputStream.skipFully(count: Int) {
    var left = count
    while (left > 0) {
        val skipped = skipBytes(left)
        if (skipped <= 0) throw EOFException()
        left -= skipped
    }
}

private const val DCT_COEFFICIENTS = 64
private const val ORIENTATION_TOLERANCE = 0.02
private const val EDGE_SNAP = 0.5f

/** Sum of the ITU-T T.81 Annex K luminance table, which libjpeg scales by quality. */
private val STANDARD_LUMINANCE_SUM = intArrayOf(
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77,
    24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101,
    72, 92, 95, 98, 112, 100, 103, 99,
).sum().toDouble()
