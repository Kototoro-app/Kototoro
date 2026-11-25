package com.kyant.backdrop

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

/** A translated surface whose lower portion is outside its host's clip. */
interface BottomClippedBackdrop {
    /** Upper bound on visible local height in physical pixels, read during drawing. */
    val visibleHeightPx: Float
}

interface Backdrop {

    val isCoordinatesDependent: Boolean

    /** Relative sampling resolution for filtered copies of this source. Foreground content draws at native size. */
    val effectInputScale: Float get() = 1f

    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null
    )
}
