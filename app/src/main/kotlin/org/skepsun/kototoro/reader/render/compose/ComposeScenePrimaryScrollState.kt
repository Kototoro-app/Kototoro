package org.skepsun.kototoro.reader.render.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Primary-axis scroll state holding high-frequency scroll position and gesture states for [ComposeSceneRenderer].
 *
 * Decouples primary reading-axis scroll offsets (vertical [scrollY] or horizontal [scrollX]) from the
 * concrete host layout orientation.
 *
 * Implements ADR 0002 Constraint 3:
 * - High-frequency scroll offset is consumed in the Draw Phase bypassing Composition/Layout.
 * - Supports [snapBy] to execute zero-CLS anchored layout compensation.
 */
@Stable
class ComposeScenePrimaryScrollState(
    initialOffset: Float = 0f,
    maxOffset: Float = Float.MAX_VALUE,
) {
    var offset: Float by mutableFloatStateOf(initialOffset)
        internal set

    var maxOffset: Float by mutableFloatStateOf(maxOffset)
        internal set

    var isDragging: Boolean by mutableStateOf(false)
        internal set

    var isFlinging: Boolean by mutableStateOf(false)
        internal set

    val isScrollInProgress: Boolean get() = isDragging || isFlinging

    fun snapTo(value: Float) {
        offset = value.coerceIn(0f, maxOffset.coerceAtLeast(0f))
    }

    fun snapBy(delta: Float) {
        snapTo(offset + delta)
    }
}

@Composable
fun rememberComposeScenePrimaryScrollState(
    initialOffset: Float = 0f,
    maxOffset: Float = Float.MAX_VALUE,
    key: Any? = null,
): ComposeScenePrimaryScrollState {
    return remember(key) { ComposeScenePrimaryScrollState(initialOffset, maxOffset) }
}
