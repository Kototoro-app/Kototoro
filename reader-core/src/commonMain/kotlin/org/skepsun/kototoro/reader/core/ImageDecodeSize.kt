package org.skepsun.kototoro.reader.core

import kotlin.math.sqrt

/** Output allocation geometry; source coordinates stay unchanged in the reader scene. */
object ImageDecodeSize {
    fun resolve(source: IntSize, maximumPixels: Long, maximumDimension: Int): IntSize {
        require(source.width > 0 && source.height > 0)
        require(maximumPixels > 0 && maximumDimension > 0)
        val scale = minOf(1.0, sqrt(maximumPixels.toDouble() / (source.width.toLong() * source.height)),
            maximumDimension.toDouble() / maxOf(source.width, source.height))
        var width = (source.width * scale).toInt().coerceAtLeast(1)
        var height = (source.height * scale).toInt().coerceAtLeast(1)
        // Clamping a very thin image to one pixel may increase its area beyond the requested budget.
        if (width.toLong() * height > maximumPixels) {
            if (width >= height) width = (maximumPixels / height).toInt().coerceAtLeast(1)
            else height = (maximumPixels / width).toInt().coerceAtLeast(1)
        }
        return IntSize(width, height)
    }
}
