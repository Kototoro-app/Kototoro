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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

enum class StableSheetAnchor {
    Full,
    ThreeQuarter,

    /** Half height, or the caller's peek height when the sheet uses a peek anchor. */
    Middle,
    Hidden,
}

const val STABLE_SHEET_THREE_QUARTER_OFFSET_FRACTION = 0.25f
const val STABLE_SHEET_HALF_OFFSET_FRACTION = 0.5f
const val STABLE_SHEET_MAX_PEEK_FRACTION = 0.6f

/**
 * Offset from the top of the host for each anchor. A peek taller than
 * [STABLE_SHEET_MAX_PEEK_FRACTION] of the host has no middle anchor (landscape, large fonts);
 * an unmeasured peek (`null` or `<= 0`) keeps the half anchor until it is measured.
 */
fun stableSheetAnchorOffsets(hostHeightPx: Float, peekHeightPx: Float?): Map<StableSheetAnchor, Float> {
    val middle = when {
        peekHeightPx == null || peekHeightPx <= 0f -> hostHeightPx * STABLE_SHEET_HALF_OFFSET_FRACTION
        peekHeightPx > hostHeightPx * STABLE_SHEET_MAX_PEEK_FRACTION -> null
        else -> hostHeightPx - peekHeightPx
    }
    return buildMap {
        put(StableSheetAnchor.Full, 0f)
        put(StableSheetAnchor.ThreeQuarter, hostHeightPx * STABLE_SHEET_THREE_QUARTER_OFFSET_FRACTION)
        middle?.let { put(StableSheetAnchor.Middle, it) }
        put(StableSheetAnchor.Hidden, hostHeightPx)
    }
}

fun stableSheetInitialAnchor(usePeekAnchor: Boolean): StableSheetAnchor =
    if (usePeekAnchor) StableSheetAnchor.Middle else StableSheetAnchor.ThreeQuarter

/** [preferred], or three quarters when [offsets] has no such anchor (a peek too tall for a middle anchor). */
fun stableSheetOpenAnchor(
    offsets: Map<StableSheetAnchor, Float>,
    preferred: StableSheetAnchor,
): StableSheetAnchor = preferred.takeIf { it in offsets } ?: StableSheetAnchor.ThreeQuarter

/**
 * The sheet slides in only once its anchors are final; opening on a guessed anchor would show it
 * in the wrong place first (at the top before the host is measured, at half before the peek is).
 */
fun stableSheetReadyToOpen(hostHeightPx: Float, peekHeightPx: Float?, waitForPeek: Boolean): Boolean =
    hostHeightPx > 0f && (!waitForPeek || peekHeightPx != null)

const val StableSheetMaxScrimAlpha = 0.42f
private const val STABLE_SHEET_PEEK_WAIT_MS = 500L

private val StableSheetAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

fun calculateStableSheetWidth(availableWidth: Dp, sheetMaxWidth: Dp?): Dp {
    return sheetMaxWidth?.coerceAtMost(availableWidth) ?: availableWidth
}

@OptIn(ExperimentalFoundationApi::class)
@Stable
private class StableSheetState(
    private val density: androidx.compose.ui.unit.Density,
    private val openAnchor: StableSheetAnchor,
    private val waitForPeek: Boolean,
    /** Without it the sheet has no peek stop, as on a landscape tablet whose peek is too tall for one. */
    private val middleAnchor: Boolean = true,
) {
    // Starts hidden and slides to openAnchor once the anchors are known (see open()).
    val anchoredState = AnchoredDraggableState(initialValue = StableSheetAnchor.Hidden)

    private var hostHeightPx by mutableFloatStateOf(0f)
    private var peekHeightPx: Float? by mutableStateOf(null)
    private var nestedDragInProgress = false

    /** Until then the sheet sits hidden only because it has not opened yet, not because it was dismissed. */
    private var hasOpened by mutableStateOf(false)

    val isReadyToOpen: Boolean
        get() = stableSheetReadyToOpen(hostHeightPx, peekHeightPx, waitForPeek)

    val hasAnchors: Boolean
        get() = anchoredState.anchors.positionOf(StableSheetAnchor.Hidden).isFinite()

    /** Unmeasured anchors mean the sheet has not opened yet, so it is below the host. */
    val offset: Float
        get() = anchoredState.offset.takeIf(Float::isFinite) ?: hostHeightPx

    val scrimAlpha: Float
        get() = if (hostHeightPx <= 0f) {
            0f
        } else {
            StableSheetMaxScrimAlpha * (1f - offset / hostHeightPx).coerceIn(0f, 1f)
        }

    val isHidden: Boolean
        get() {
            val hiddenOffset = anchoredState.anchors.positionOf(StableSheetAnchor.Hidden)
            return hasOpened &&
                hiddenOffset.isFinite() &&
                anchoredState.settledValue == StableSheetAnchor.Hidden &&
                anchoredState.targetValue == StableSheetAnchor.Hidden &&
                !anchoredState.isAnimationRunning &&
                abs(offset - hiddenOffset) < HIDDEN_OFFSET_TOLERANCE_PX
        }

    fun updateHostHeight(heightPx: Float) {
        if (heightPx <= 0f || hostHeightPx == heightPx) return
        hostHeightPx = heightPx
        updateAnchors()
    }

    fun updatePeekHeight(heightPx: Float?) {
        if (peekHeightPx == heightPx) return
        peekHeightPx = heightPx
        if (hostHeightPx > 0f) updateAnchors()
    }

    private fun updateAnchors() {
        val offsets = stableSheetAnchorOffsets(hostHeightPx, peekHeightPx)
            .let { if (middleAnchor) it else it - StableSheetAnchor.Middle }
        val target = anchoredState.targetValue.takeIf { it in offsets } ?: StableSheetAnchor.ThreeQuarter
        anchoredState.updateAnchors(
            DraggableAnchors { offsets.forEach { (anchor, position) -> anchor at position } },
            target,
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

    suspend fun open() {
        if (hasOpened) return
        hasOpened = true
        val target = stableSheetOpenAnchor(stableSheetAnchorOffsets(hostHeightPx, peekHeightPx), openAnchor)
        anchoredState.animateTo(target, animationSpec = StableSheetAnimationSpec)
    }

    suspend fun dismiss(): Boolean {
        hasOpened = true
        if (!anchoredState.anchors.positionOf(StableSheetAnchor.Hidden).isFinite()) return false
        anchoredState.animateTo(StableSheetAnchor.Hidden, animationSpec = StableSheetAnimationSpec)
        return true
    }

    private fun targetAnchor(velocityY: Float): StableSheetAnchor {
        val visibleAnchors = StableSheetAnchor.entries.filter { anchoredState.anchors.positionOf(it).isFinite() }
        val current = anchoredState.settledValue
        val currentOffset = anchoredState.anchors.positionOf(current)
        val currentIndex = visibleAnchors.indexOf(current)
        val velocityThreshold = with(density) { 96.dp.toPx() }
        val direction = when {
            abs(velocityY) >= velocityThreshold -> if (velocityY < 0f) -1 else 1
            offset < currentOffset -> -1
            offset > currentOffset -> 1
            else -> 0
        }
        val adjacent = visibleAnchors.getOrNull(currentIndex + direction) ?: return current
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

/** What [StableAnchoredSheetLayout] hands to its sheet slot. */
@Immutable
class StableSheetSlotScope internal constructor(
    /** Apply to the drag handle / header so they drag the sheet. */
    val dragModifier: Modifier,
    /** Bottom padding that keeps content inside the visible part of the sheet. */
    val contentBottomPadding: Dp,
    /** Animates the sheet away, then calls `onDismissRequest`. */
    val dismiss: () -> Unit,
    /** Expanded beyond an available peek anchor, which can restore the header when dragged down. */
    val isExpandedPastPeek: Boolean = false,
)

/**
 * The anchored sheet without a window of its own, so it can live in the same window as a
 * `layerBackdrop` and draw real glass over it. Handles back itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StableAnchoredSheetLayout(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetMaxWidth: Dp? = null,
    scrimColor: Color = Color.Black.copy(alpha = StableSheetMaxScrimAlpha),
    usePeekAnchor: Boolean = false,
    peekHeight: Dp? = null,
    /**
     * Opens at three quarters with no peek stop, as a landscape tablet does when the peek is too tall for one; the
     * header then stays in view (it only collapses when a sheet is dragged past an available peek).
     */
    openExpanded: Boolean = false,
    sheet: @Composable (StableSheetSlotScope) -> Unit,
) {
    val density = LocalDensity.current
    val state = remember(density) {
        StableSheetState(
            density,
            if (openExpanded) StableSheetAnchor.ThreeQuarter else stableSheetInitialAnchor(usePeekAnchor),
            waitForPeek = usePeekAnchor,
            middleAnchor = !openExpanded,
        )
    }
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
    LaunchedEffect(state) {
        snapshotFlow { state.isHidden }
            .filter { it }
            .first()
        currentOnDismissRequest.value()
    }
    val peekHeightPx = peekHeight?.let { with(density) { it.toPx() } }
    LaunchedEffect(state, peekHeightPx) { state.updatePeekHeight(peekHeightPx) }
    LaunchedEffect(state) {
        // A peek that never arrives must not keep the sheet closed.
        withTimeoutOrNull(STABLE_SHEET_PEEK_WAIT_MS) {
            snapshotFlow { state.isReadyToOpen }.first { it }
        }
        snapshotFlow { state.hasAnchors }.first { it }
        state.open()
    }
    PlatformBackHandler(onBack = dismissWithAnimation)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.updateHostHeight(it.height.toFloat()) },
    ) {
        val sheetWidth = calculateStableSheetWidth(maxWidth, sheetMaxWidth)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    scrimColor.copy(
                        alpha = (scrimColor.alpha * state.scrimAlpha / StableSheetMaxScrimAlpha)
                            .coerceIn(0f, 1f),
                    ),
                )
                .clickable(onClick = dismissWithAnimation),
        )
        // Before the first measure pass the host height is unknown, so place the sheet by the constraints.
        val offset = state.anchoredState.offset.takeIf(Float::isFinite)?.coerceAtLeast(0f)
            ?: constraints.maxHeight.toFloat()
        Box(
            modifier = Modifier
                .width(sheetWidth)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, offset.roundToInt()) }
                .nestedScroll(nestedScrollConnection),
        ) {
            sheet(
                StableSheetSlotScope(
                    dragModifier = sheetDragModifier,
                    contentBottomPadding = with(density) { offset.toDp() },
                    dismiss = dismissWithAnimation,
                    isExpandedPastPeek = usePeekAnchor &&
                        state.anchoredState.anchors.positionOf(StableSheetAnchor.Middle).isFinite() &&
                        (state.anchoredState.targetValue == StableSheetAnchor.Full ||
                            state.anchoredState.targetValue == StableSheetAnchor.ThreeQuarter),
                ),
            )
        }
    }
}
