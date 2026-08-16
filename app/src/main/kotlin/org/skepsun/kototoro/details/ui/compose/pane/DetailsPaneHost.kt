package org.skepsun.kototoro.details.ui.compose.pane

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneFlingBehavior

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsPaneHost(
    state: DetailsPaneState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val flingBehavior = rememberDetailsPaneFlingBehavior(state)
    Box(
        modifier = modifier
            .graphicsLayer {
                translationY = state.translationY
            }
            .anchoredDraggable(
                state = state.anchoredState,
                orientation = Orientation.Vertical,
                enabled = state.isPaneSurfaceDragEnabled,
                flingBehavior = flingBehavior,
            ),
        content = content,
    )
}
