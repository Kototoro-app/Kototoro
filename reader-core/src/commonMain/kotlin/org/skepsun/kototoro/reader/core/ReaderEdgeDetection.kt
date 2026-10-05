package org.skepsun.kototoro.reader.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The reader's "crop pages" margin detection (Android's `EdgeDetector`), platform-free: pages are scanned in
 * [BLOCK_SIZE] blocks from each edge until a non-white pixel; a margin wider than a third of the page means the page
 * has no plain margin to crop. Pixels come from a downsampled decode through [BlockReader] (Android: `Bitmap.getPixels`,
 * Windows: a Skia bitmap), so the decoder stays the platform's.
 */
object ReaderEdgeDetection {
    const val BLOCK_SIZE = 100
    const val COLOR_TOLERANCE = 16
    private const val WHITE = -0x1 // 0xFFFFFFFF

    /** Fills [out] (row-major, [width] wide) with the ARGB pixels of the block at ([x], [y]) of the sampled bitmap. */
    fun interface BlockReader {
        fun read(out: IntArray, x: Int, y: Int, width: Int, height: Int)
    }

    /** The decode sample size for a page: large pages are scanned downscaled without losing the edges. */
    fun sampleSize(width: Int, height: Int): Int {
        val maxDimension = max(width, height)
        val scale = when {
            maxDimension <= 1024 -> 1.0f
            maxDimension <= 2048 -> 0.75f
            maxDimension <= 4096 -> 0.5f
            else -> 0.25f
        }
        return (1f / scale).toInt().coerceAtLeast(1)
    }

    /**
     * The page's content bounds in source pixels, or null when it has no margin or one edge is not plain (nothing to
     * crop). [width]×[height] is the source size; the bitmap read through [reader] was decoded with [sampleSize].
     */
    fun contentBounds(width: Int, height: Int, sampleSize: Int, bitmapWidth: Int, bitmapHeight: Int,
        reader: BlockReader): IntRect? {
        val edges = intArrayOf(
            horizontalEdge(width, height, sampleSize, bitmapWidth, bitmapHeight, reader, fromLeft = true),
            verticalEdge(width, height, sampleSize, bitmapWidth, bitmapHeight, reader, fromTop = true),
            horizontalEdge(width, height, sampleSize, bitmapWidth, bitmapHeight, reader, fromLeft = false),
            verticalEdge(width, height, sampleSize, bitmapWidth, bitmapHeight, reader, fromTop = false),
        )
        if (edges.any { it < 0 } || edges.none { it > 0 }) return null
        val left = edges[0]
        val top = edges[1]
        val right = width - edges[2]
        val bottom = height - edges[3]
        if (left >= right || top >= bottom) return null
        return IntRect(left, top, right, bottom)
    }

    fun isColorTheSame(a: Int, b: Int, tolerance: Int): Boolean =
        abs(red(a) - red(b)) <= tolerance && abs(green(a) - green(b)) <= tolerance &&
            abs(blue(a) - blue(b)) <= tolerance && abs(alpha(a) - alpha(b)) <= tolerance

    private fun Int.isNotWhite() = !isColorTheSame(this, WHITE, COLOR_TOLERANCE)

    private fun horizontalEdge(width: Int, height: Int, sampleSize: Int, bitmapWidth: Int, bitmapHeight: Int,
        reader: BlockReader, fromLeft: Boolean): Int {
        var edge = width
        val rectCount = width / BLOCK_SIZE
        val maxRect = rectCount / 3
        val block = IntArray(BLOCK_SIZE * BLOCK_SIZE)
        for (i in 0 until rectCount) {
            if (i > maxRect) return -1
            var remaining = BLOCK_SIZE
            for (j in 0 until height / BLOCK_SIZE) {
                val regionX = if (fromLeft) i * BLOCK_SIZE else width - (i + 1) * BLOCK_SIZE
                val bitmapX = regionX / sampleSize
                val bitmapY = j * BLOCK_SIZE / sampleSize
                val blockWidth = min(BLOCK_SIZE / sampleSize, bitmapWidth - bitmapX)
                val blockHeight = min(BLOCK_SIZE / sampleSize, bitmapHeight - bitmapY)
                if (blockWidth > 0 && blockHeight > 0) {
                    reader.read(block, bitmapX, bitmapY, blockWidth, blockHeight)
                    for (ii in 0 until min(blockWidth, remaining / sampleSize)) {
                        for (jj in 0 until blockHeight) {
                            val column = if (fromLeft) ii else blockWidth - ii - 1
                            if (block[jj * blockWidth + column].isNotWhite()) {
                                edge = min(edge, BLOCK_SIZE * i + ii * sampleSize)
                                remaining -= sampleSize
                                break
                            }
                        }
                    }
                }
                if (remaining == 0) break
            }
            if (remaining < BLOCK_SIZE) break
        }
        return edge
    }

    private fun verticalEdge(width: Int, height: Int, sampleSize: Int, bitmapWidth: Int, bitmapHeight: Int,
        reader: BlockReader, fromTop: Boolean): Int {
        var edge = height
        val rectCount = height / BLOCK_SIZE
        val maxRect = rectCount / 3
        val block = IntArray(BLOCK_SIZE * BLOCK_SIZE)
        for (j in 0 until rectCount) {
            if (j > maxRect) return -1
            var remaining = BLOCK_SIZE
            for (i in 0 until width / BLOCK_SIZE) {
                val regionY = if (fromTop) j * BLOCK_SIZE else height - (j + 1) * BLOCK_SIZE
                val bitmapX = i * BLOCK_SIZE / sampleSize
                val bitmapY = regionY / sampleSize
                val blockWidth = min(BLOCK_SIZE / sampleSize, bitmapWidth - bitmapX)
                val blockHeight = min(BLOCK_SIZE / sampleSize, bitmapHeight - bitmapY)
                if (blockWidth > 0 && blockHeight > 0) {
                    reader.read(block, bitmapX, bitmapY, blockWidth, blockHeight)
                    for (jj in 0 until min(blockHeight, remaining / sampleSize)) {
                        for (ii in 0 until blockWidth) {
                            val row = if (fromTop) jj else blockHeight - jj - 1
                            if (block[row * blockWidth + ii].isNotWhite()) {
                                edge = min(edge, BLOCK_SIZE * j + jj * sampleSize)
                                remaining -= sampleSize
                                break
                            }
                        }
                    }
                }
                if (remaining == 0) break
            }
            if (remaining < BLOCK_SIZE) break
        }
        return edge
    }

    private fun alpha(color: Int) = color ushr 24 and 0xFF
    private fun red(color: Int) = color shr 16 and 0xFF
    private fun green(color: Int) = color shr 8 and 0xFF
    private fun blue(color: Int) = color and 0xFF
}
