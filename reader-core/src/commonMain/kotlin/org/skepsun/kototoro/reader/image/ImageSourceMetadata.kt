package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntSize

/**
 * Fundamental image source metadata discovered prior to full pixel decoding.
 */
data class ImageSourceMetadata(
    val size: IntSize,
    val mimeType: String? = null,
    val isAnimated: Boolean = false,
) {
    init {
        require(!size.isEmpty) { "size must not be empty: $size" }
    }
}
