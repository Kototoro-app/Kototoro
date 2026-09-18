package org.skepsun.kototoro.reader.render.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * State object holding high-frequency vertical scroll position and gestures for [ComposeSceneRenderer].
 *
 * Backed by [ComposeScenePrimaryScrollState] to share scroll state mechanics across orientations.
 *
 * Implements ADR 0002 Constraint 3:
 * - High-frequency scroll offset is consumed in the Draw Phase bypassing Composition/Layout.
 * - Supports [snapBy] to execute zero-CLS anchored layout compensation.
 */
@Stable
class ComposeSceneScrollState(
    initialScrollY: Float = 0f,
    maxScrollY: Float = Float.MAX_VALUE,
) {
    val primaryState = ComposeScenePrimaryScrollState(initialScrollY, maxScrollY)

    var scrollY: Float
        get() = primaryState.offset
        internal set(value) {
            primaryState.offset = value
        }

    var maxScrollY: Float
        get() = primaryState.maxOffset
        internal set(value) {
            primaryState.maxOffset = value
        }

    var isDragging: Boolean
        get() = primaryState.isDragging
        internal set(value) {
            primaryState.isDragging = value
        }

    var isFlinging: Boolean
        get() = primaryState.isFlinging
        internal set(value) {
            primaryState.isFlinging = value
        }

    val isScrollInProgress: Boolean get() = primaryState.isScrollInProgress

    fun snapTo(value: Float) {
        primaryState.snapTo(value)
    }

    fun snapBy(delta: Float) {
        primaryState.snapBy(delta)
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
