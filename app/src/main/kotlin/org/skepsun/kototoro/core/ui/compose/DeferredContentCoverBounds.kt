package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

class DeferredContentCoverBounds<T>(
    private val resolveBounds: (T) -> Rect?,
) {
    private var coordinates: T? = null

    fun updateCoordinates(value: T) {
        coordinates = value
    }

    fun currentBounds(): Rect? = coordinates?.let(resolveBounds)
}

@Composable
fun rememberDeferredContentCoverBounds(): DeferredContentCoverBounds<LayoutCoordinates> = remember {
    DeferredContentCoverBounds { coordinates ->
        coordinates.takeIf(LayoutCoordinates::isAttached)?.unclippedBoundsInWindow()
    }
}
