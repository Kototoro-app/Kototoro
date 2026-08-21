package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class StableSheetAnchor {
    Full,
    ThreeQuarter,
    Half,
    Hidden,
}

private val StableSheetAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

@OptIn(ExperimentalFoundationApi::class)
@Stable
private class StableSheetState(
    private val density: androidx.compose.ui.unit.Density,
) {
    val anchoredState = AnchoredDraggableState(initialValue = StableSheetAnchor.ThreeQuarter)

    private var hostHeightPx by mutableFloatStateOf(0f)
    private var nestedDragInProgress = false

    val offset: Float
        get() = anchoredState.offset.takeIf(Float::isFinite) ?: hostHeightPx * THREE_QUARTER_OFFSET_FRACTION

    val scrimAlpha: Float
        get() = if (hostHeightPx <= 0f) {
            MAX_SCRIM_ALPHA
        } else {
            MAX_SCRIM_ALPHA * (1f - offset / hostHeightPx).coerceIn(0f, 1f)
        }

    val isHidden: Boolean
        get() {
            val hiddenOffset = anchoredState.anchors.positionOf(StableSheetAnchor.Hidden)
            return hiddenOffset.isFinite() &&
                anchoredState.settledValue == StableSheetAnchor.Hidden &&
                anchoredState.targetValue == StableSheetAnchor.Hidden &&
                !anchoredState.isAnimationRunning &&
                abs(offset - hiddenOffset) < HIDDEN_OFFSET_TOLERANCE_PX
        }

    fun updateHostHeight(heightPx: Float) {
        if (heightPx <= 0f || hostHeightPx == heightPx) return
        hostHeightPx = heightPx
        anchoredState.updateAnchors(
            DraggableAnchors {
                StableSheetAnchor.Full at 0f
                StableSheetAnchor.ThreeQuarter at heightPx * THREE_QUARTER_OFFSET_FRACTION
                StableSheetAnchor.Half at heightPx * HALF_OFFSET_FRACTION
                StableSheetAnchor.Hidden at heightPx
            },
            anchoredState.targetValue,
        )
    }

    fun dispatchNestedDelta(deltaY: Float): Float {
        val consumed = anchoredState.dispatchRawDelta(deltaY)
        if (consumed != 0f) nestedDragInProgress = true
        return consumed
    }

    fun hasNestedDrag(): Boolean = nestedDragInProgress

    suspend fun settle(velocityY: Float): StableSheetAnchor {
        nestedDragInProgress = false
        val target = targetAnchor(velocityY)
        anchoredState.animateTo(target, animationSpec = StableSheetAnimationSpec)
        return target
    }

    suspend fun dismiss(): Boolean {
        if (!anchoredState.anchors.positionOf(StableSheetAnchor.Hidden).isFinite()) return false
        anchoredState.animateTo(StableSheetAnchor.Hidden, animationSpec = StableSheetAnimationSpec)
        return true
    }

    private fun targetAnchor(velocityY: Float): StableSheetAnchor {
        val current = anchoredState.settledValue
        val currentOffset = anchoredState.anchors.positionOf(current)
        val currentIndex = VisibleAnchors.indexOf(current)
        val velocityThreshold = with(density) { 96.dp.toPx() }
        val direction = when {
            abs(velocityY) >= velocityThreshold -> if (velocityY < 0f) -1 else 1
            offset < currentOffset -> -1
            offset > currentOffset -> 1
            else -> 0
        }
        val adjacent = VisibleAnchors.getOrNull(currentIndex + direction) ?: return current
        if (abs(velocityY) >= velocityThreshold) return adjacent
        val adjacentOffset = anchoredState.anchors.positionOf(adjacent)
        return if (abs(offset - currentOffset) >= positionalThreshold(abs(adjacentOffset - currentOffset))) {
            adjacent
        } else {
            current
        }
    }

    fun positionalThreshold(distance: Float): Float {
        return minOf(
            distance * DEFAULT_POSITIONAL_THRESHOLD_FRACTION,
            with(density) { MAX_POSITIONAL_THRESHOLD.toPx() },
        )
    }

    private companion object {
        val VisibleAnchors = listOf(
            StableSheetAnchor.Full,
            StableSheetAnchor.ThreeQuarter,
            StableSheetAnchor.Half,
            StableSheetAnchor.Hidden,
        )
        const val THREE_QUARTER_OFFSET_FRACTION = 0.25f
        const val HALF_OFFSET_FRACTION = 0.5f
        const val MAX_SCRIM_ALPHA = 0.42f
        const val DEFAULT_POSITIONAL_THRESHOLD_FRACTION = 0.28f
        val MAX_POSITIONAL_THRESHOLD = 48.dp
        const val HIDDEN_OFFSET_TOLERANCE_PX = 0.5f
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberStableSheetNestedScrollConnection(
    state: StableSheetState,
): NestedScrollConnection {
    return remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val isPartiallyExpanded = state.offset > 0f
                if (!state.hasNestedDrag() && (!isPartiallyExpanded || available.y == 0f)) return Offset.Zero
                val consumed = state.dispatchNestedDelta(available.y)
                return Offset(0f, if (state.hasNestedDrag() && consumed == 0f) available.y else consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                return Offset(0f, state.dispatchNestedDelta(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.hasNestedDrag()) return Velocity.Zero
                state.settle(available.y)
                return Velocity(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!state.hasNestedDrag()) return Velocity.Zero
                val velocity = available.y.takeIf { it != 0f } ?: consumed.y
                state.settle(velocity)
                return Velocity(0f, velocity)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StableAnchoredBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable (dragModifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val state = remember(density) { StableSheetState(density) }
    val coroutineScope = rememberCoroutineScope()
    val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val nestedScrollConnection = rememberStableSheetNestedScrollConnection(state)
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state.anchoredState,
        state::positionalThreshold,
        StableSheetAnimationSpec,
    )
    val sheetDragModifier = Modifier.anchoredDraggable(
        state = state.anchoredState,
        orientation = Orientation.Vertical,
        flingBehavior = flingBehavior,
    )
    val dismissWithAnimation = remember(state, coroutineScope, currentOnDismissRequest) {
        {
            coroutineScope.launch {
                if (!state.dismiss()) currentOnDismissRequest.value()
            }
            Unit
        }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        snapshotFlow { state.isHidden }
            .filter { it }
            .first()
        currentOnDismissRequest.value()
    }

    Dialog(
        onDismissRequest = dismissWithAnimation,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { state.updateHostHeight(it.height.toFloat()) },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = state.scrimAlpha))
                    .clickable(onClick = dismissWithAnimation),
            )
            val offset = state.offset.coerceAtLeast(0f)
            Surface(
                shape = shape,
                color = containerColor,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, offset.roundToInt()) }
                    .nestedScroll(nestedScrollConnection),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = with(density) { offset.toDp() }),
                ) {
                    if (dragHandle != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(sheetDragModifier),
                        ) {
                            dragHandle()
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        content(sheetDragModifier)
                    }
                }
            }
        }
    }
}
