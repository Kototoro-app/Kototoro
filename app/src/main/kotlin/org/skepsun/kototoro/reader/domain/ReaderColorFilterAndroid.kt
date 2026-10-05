package org.skepsun.kototoro.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/** The shared matrix (core-ui `ReaderColorMatrix`) as an Android [ColorMatrix]. */
fun ReaderColorFilter.toColorMatrix(): ColorMatrix = ColorMatrix(matrix())

fun ReaderColorFilter.toColorFilter(): ColorMatrixColorFilter = ColorMatrixColorFilter(toColorMatrix())

fun ReaderColorFilter.getBackgroundTint(): ColorStateList? = if (isBookBackground) {
    val color = Color.rgb(255, 255, (255 * ReaderColorMatrix.BOOK_BLUE_FACTOR).toInt())
    ColorStateList.valueOf(color)
} else {
    null
}
