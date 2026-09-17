package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize

/**
 * Geometric mapping between raw encoded image bytes and logical page space.
 *
 * Accounts for:
 * 1. Cropping margins (contentRect relative to encodedSize)
 * 2. Split pages (left/right halves of double-page spreads)
 * 3. EXIF / sensor orientation rotation
 */
data class ImageSourceGeometry(
    val encodedSize: IntSize,
    val contentRect: IntRect = IntRect.fromLtwh(0, 0, encodedSize.width, encodedSize.height),
    val orientationDegrees: Int = 0,
) {
    init {
        require(!encodedSize.isEmpty) { "encodedSize must not be empty: $encodedSize" }
        require(contentRect.left >= 0 && contentRect.top >= 0) { "contentRect must be non-negative: $contentRect" }
        require(contentRect.right <= encodedSize.width && contentRect.bottom <= encodedSize.height) {
            "contentRect $contentRect exceeds encodedSize $encodedSize"
        }
        require(orientationDegrees in setOf(0, 90, 180, 270)) {
            "orientationDegrees must be 0, 90, 180, or 270: $orientationDegrees"
        }
    }

    /**
     * Logical size of the page as presented to layout and renderer.
     */
    val logicalSize: IntSize
        get() = if (orientationDegrees == 90 || orientationDegrees == 270) {
            IntSize(contentRect.height, contentRect.width)
        } else {
            IntSize(contentRect.width, contentRect.height)
        }

    /**
     * Maps a logical region (relative to logical [0, 0, logicalSize.width, logicalSize.height])
     * back into raw encoded pixel coordinates for [BitmapRegionDecoder].
     */
    fun mapLogicalToEncodedRegion(logicalRegion: IntRect): IntRect {
        require(logicalRegion.left >= 0 && logicalRegion.top >= 0) { "logicalRegion must be non-negative" }

        val clampedLogical = IntRect(
            left = logicalRegion.left.coerceIn(0, logicalSize.width),
            top = logicalRegion.top.coerceIn(0, logicalSize.height),
            right = logicalRegion.right.coerceIn(0, logicalSize.width),
            bottom = logicalRegion.bottom.coerceIn(0, logicalSize.height),
        )

        val unrotated = when (orientationDegrees) {
            0 -> clampedLogical
            90 -> IntRect(
                left = clampedLogical.top,
                top = logicalSize.width - clampedLogical.right,
                right = clampedLogical.bottom,
                bottom = logicalSize.width - clampedLogical.left,
            )
            180 -> IntRect(
                left = logicalSize.width - clampedLogical.right,
                top = logicalSize.height - clampedLogical.bottom,
                right = logicalSize.width - clampedLogical.left,
                bottom = logicalSize.height - clampedLogical.top,
            )
            270 -> IntRect(
                left = logicalSize.height - clampedLogical.bottom,
                top = clampedLogical.left,
                right = logicalSize.height - clampedLogical.top,
                bottom = clampedLogical.right,
            )
            else -> clampedLogical
        }

        // Translate relative to contentRect offset inside raw encoded dimensions
        return IntRect(
            left = contentRect.left + unrotated.left,
            top = contentRect.top + unrotated.top,
            right = contentRect.left + unrotated.right,
            bottom = contentRect.top + unrotated.bottom,
        )
    }
}
