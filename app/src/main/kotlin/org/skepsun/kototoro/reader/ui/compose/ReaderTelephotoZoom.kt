package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Size
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.DynamicZoomSpec
import me.saket.telephoto.zoomable.ZoomSpec

internal val ReaderTelephotoZoomSpec = DynamicZoomSpec { inputs ->
    ZoomSpec(
        maxZoomFactor = calculateReaderTelephotoMaxZoomFactor(
            unscaledContentSize = inputs.unscaledContentSize,
            scaledContentSize = inputs.scaledContentBounds.size,
        ),
    )
}

internal val ReaderTelephotoDoubleClickListener = DoubleClickToZoomListener { state, centroid ->
    if ((state.zoomFraction ?: 0f) > READER_ZOOM_EPSILON) {
        state.resetZoom()
    } else {
        state.zoomBy(READER_DOUBLE_TAP_ZOOM_SCALE, centroid)
    }
}

internal fun calculateReaderTelephotoMaxZoomFactor(
    unscaledContentSize: Size,
    scaledContentSize: Size,
): Float {
    val scaleX = scaledContentSize.width / unscaledContentSize.width
    val scaleY = scaledContentSize.height / unscaledContentSize.height
    val fittedScale = maxOf(scaleX, scaleY).takeIf { it.isFinite() && it > 0f } ?: 1f
    return fittedScale * READER_MAX_ZOOM_SCALE
}
