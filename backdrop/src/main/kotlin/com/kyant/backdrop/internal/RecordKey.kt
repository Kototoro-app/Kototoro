package com.kyant.backdrop.internal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection

/**
 * Inputs of a recorded shadow, inner shadow or highlight layer: the layer is recorded again only when they change.
 *
 * [shape] is [ShapeProvider.innerShape], not [ShapeProvider.shape]: a new [ShapeProvider] is created on every
 * recomposition, so its wrapper shape is never equal to the previous one.
 * [params] is the shadow / inner shadow / highlight with alpha 1, as alpha is set on the layer without recording it.
 */
internal data class RecordKey(
    val size: Size,
    val density: Float,
    val layoutDirection: LayoutDirection,
    val shape: Shape,
    val params: Any
)
