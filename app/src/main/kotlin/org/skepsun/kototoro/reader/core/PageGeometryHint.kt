package org.skepsun.kototoro.reader.core

/**
 * Declares the known geometric dimensions or aspect ratio hint for a page.
 *
 * Used by [PageGeometry] and ReaderScene to compute page layout without coupling
 * to image decoding status (ADR 0002 Constraint 1).
 */
sealed interface PageGeometryHint {

    /** Exact pixel dimensions obtained from image header or decode. */
    data class Exact(
        val width: Int,
        val height: Int,
    ) : PageGeometryHint {
        init {
            require(width > 0) { "width must be > 0: $width" }
            require(height > 0) { "height must be > 0: $height" }
        }

        val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    }

    /** Known intrinsic aspect ratio (width / height) before full dimensions are resolved. */
    data class AspectRatio(
        val ratio: Float,
    ) : PageGeometryHint {
        init {
            require(ratio > 0f && ratio.isFinite()) { "ratio must be > 0 and finite: $ratio" }
        }
    }

    /** Heuristic or placeholder ratio used while awaiting headers/metadata. */
    data class Estimated(
        val ratio: Float,
    ) : PageGeometryHint {
        init {
            require(ratio > 0f && ratio.isFinite()) { "ratio must be > 0 and finite: $ratio" }
        }
    }
}
