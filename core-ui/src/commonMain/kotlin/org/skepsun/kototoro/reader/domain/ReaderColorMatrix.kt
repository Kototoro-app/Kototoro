package org.skepsun.kototoro.reader.domain

/**
 * The reader's colour filter (Android's `ReaderColorFilter`) as a 4×5 colour matrix: row-major, the fifth column a
 * translation in 0..255 — the layout both `android.graphics.ColorMatrix` and Compose's `ColorMatrix` take. The
 * operations replay Android's exactly: grayscale replaces the identity with `setSaturation(0)`, every later step is
 * `postConcat` (the step's matrix multiplied on the left).
 */
object ReaderColorMatrix {
    /** Blue kept by the "book" (eye-care) effect, which also tints the page background. */
    const val BOOK_BLUE_FACTOR = 0.92f

    fun of(brightness: Float, contrast: Float, inverted: Boolean, grayscale: Boolean, bookBackground: Boolean): FloatArray {
        var matrix = if (grayscale) saturation(0f) else identity()
        if (inverted) matrix = concat(INVERT, matrix)
        val scale = brightness + 1f
        matrix = concat(floatArrayOf(
            scale, 0f, 0f, 0f, 0f,
            0f, scale, 0f, 0f, 0f,
            0f, 0f, scale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ), matrix)
        val contrastScale = contrast + 1f
        val translate = (-.5f * contrastScale + .5f) * 255f
        matrix = concat(floatArrayOf(
            contrastScale, 0f, 0f, 0f, translate,
            0f, contrastScale, 0f, 0f, translate,
            0f, 0f, contrastScale, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ), matrix)
        if (bookBackground) matrix = concat(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, BOOK_BLUE_FACTOR, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ), matrix)
        return matrix
    }

    /** Whether the settings leave pages untouched. */
    fun isEmpty(brightness: Float, contrast: Float, inverted: Boolean, grayscale: Boolean, bookBackground: Boolean) =
        !grayscale && !inverted && !bookBackground && brightness == 0f && contrast == 0f

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** `ColorMatrix.setSaturation`: the luminance weights Android uses. */
    fun saturation(saturation: Float): FloatArray {
        val inverse = 1f - saturation
        val r = .213f * inverse
        val g = .715f * inverse
        val b = .072f * inverse
        return floatArrayOf(
            r + saturation, g, b, 0f, 0f,
            r, g + saturation, b, 0f, 0f,
            r, g, b + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** `ColorMatrix.setConcat(a, b)`: a × b with the implicit fifth row (0, 0, 0, 0, 1). */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(20)
        for (row in 0 until 4) for (column in 0 until 5) {
            result[row * 5 + column] = a[row * 5] * b[column] + a[row * 5 + 1] * b[5 + column] +
                a[row * 5 + 2] * b[10 + column] + a[row * 5 + 3] * b[15 + column] +
                if (column == 4) a[row * 5 + 4] else 0f
        }
        return result
    }

    private val INVERT = floatArrayOf(
        -1f, 0f, 0f, 1f, 1f,
        0f, -1f, 0f, 1f, 1f,
        0f, 0f, -1f, 1f, 1f,
        0f, 0f, 0f, 1f, 0f,
    )
}
