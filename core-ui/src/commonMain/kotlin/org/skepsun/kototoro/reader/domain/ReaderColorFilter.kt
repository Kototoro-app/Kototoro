package org.skepsun.kototoro.reader.domain

/** The reader's colour correction; [matrix] is the shared [ReaderColorMatrix], identical on every platform. */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
) {

    val isEmpty: Boolean
        get() = ReaderColorMatrix.isEmpty(brightness, contrast, isInverted, isGrayscale, isBookBackground)

    fun matrix(): FloatArray = ReaderColorMatrix.of(brightness, contrast, isInverted, isGrayscale, isBookBackground)

    companion object {

        val EMPTY = ReaderColorFilter(
            brightness = 0.0f,
            contrast = 0.0f,
            isInverted = false,
            isGrayscale = false,
            isBookBackground = false,
        )
    }
}
