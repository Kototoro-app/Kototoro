package org.skepsun.kototoro.reader.image

/**
 * Platform-agnostic raster format describing pixel memory density.
 *
 * Conforms to ADR 0002 Invariant I1: Pure Kotlin, zero Android `Bitmap.Config` dependencies.
 */
enum class RasterFormat(
    val estimatedBytesPerPixel: Int,
) {
    RGB_565(2),
    ARGB_8888(4),
}
