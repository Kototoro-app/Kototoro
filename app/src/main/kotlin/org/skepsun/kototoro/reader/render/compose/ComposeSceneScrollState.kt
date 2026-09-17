package org.skepsun.kototoro.reader.render.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * State object holding high-frequency scroll position and gestures for [ComposeSceneRenderer].
 *
 * Implements ADR 0002 Constraint 3:
 * - High-frequency scroll offset is consumed in the Draw Phase bypassing Composition/Layout.
 * - Supports [snapBy] to execute zero-CLS [VerticalReaderScene.AnchorCompensation] when page
 *   geometry is updated from estimated to exact dimensions.
 */
@Stable
class ComposeSceneScrollState(
    initialScrollY: Float = 0f,
    maxScrollY: Float = Float.MAX_VALUE,
) {
    var scrollY by mutableFloatStateOf(initialScrollY)
        internal set

    var maxScrollY by mutableFloatStateOf(maxScrollY)
        internal set

    var isDragging by mutableStateOf(false)
        internal set

    var isFlinging by mutableStateOf(false)
        internal set

    val isScrollInProgress: Boolean get() = isDragging || isFlinging

    fun snapTo(value: Float) {
        scrollY = value.coerceIn(0f, maxScrollY)
    }

    fun snapBy(delta: Float) {
        scrollY = (scrollY + delta).coerceIn(0f, maxScrollY)
    }
}

@Composable
fun rememberComposeSceneScrollState(
    initialScrollY: Float = 0f,
    maxScrollY: Float = Float.MAX_VALUE,
    key: Any? = null,
): ComposeSceneScrollState {
    return remember(key) { ComposeSceneScrollState(initialScrollY, maxScrollY) }
}
