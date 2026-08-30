package org.skepsun.kototoro.main.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Two anchors only: fully open, or hidden (pull the handle down to close).
 * There are no partial states, so the wizard always rests full-screen and the
 * only gesture that moves the panel is dragging the top handle.
 */
private enum class WelcomeSheetAnchor {
    Open,
    Hidden,
}

private val WelcomeSheetSettleAnimation = tween<Float>(durationMillis = 280)

@OptIn(ExperimentalFoundationApi::class)
@Stable
private class WelcomeSheetState(private val density: Density) {
    val anchored = AnchoredDraggableState(initialValue = WelcomeSheetAnchor.Open)

    private var hostHeightPx by mutableFloatStateOf(0f)

    val offset: Float
        get() = anchored.offset.takeIf(Float::isFinite) ?: 0f

    val scrimAlpha: Float
        get() = if (hostHeightPx <= 0f) {
            MAX_SCRIM_ALPHA
        } else {
            MAX_SCRIM_ALPHA * (1f - offset / hostHeightPx).coerceIn(0f, 1f)
        }

    fun updateHeight(px: Float) {
        if (px <= 0f || hostHeightPx == px) return
        hostHeightPx = px
        anchored.updateAnchors(
            DraggableAnchors {
                WelcomeSheetAnchor.Open at 0f
                WelcomeSheetAnchor.Hidden at px
            },
            anchored.targetValue,
        )
    }

    /** Called while the top handle is being dragged: panel follows the finger. */
    fun drag(deltaY: Float) {
        anchored.dispatchRawDelta(deltaY)
    }

    /** Called when the handle is released: settle on Open or dismiss on Hidden. */
    suspend fun settle(velocityY: Float) {
        val velocityThreshold = with(density) { VELOCITY_THRESHOLD.toPx() }
        val dismissDistance = hostHeightPx * DISMISS_FRACTION
        val target = when {
            velocityY > velocityThreshold -> WelcomeSheetAnchor.Hidden
            velocityY < -velocityThreshold -> WelcomeSheetAnchor.Open
            offset > dismissDistance -> WelcomeSheetAnchor.Hidden
            else -> WelcomeSheetAnchor.Open
        }
        anchored.animateTo(target, animationSpec = WelcomeSheetSettleAnimation)
    }

    suspend fun hide() {
        anchored.animateTo(WelcomeSheetAnchor.Hidden, animationSpec = WelcomeSheetSettleAnimation)
    }

    private companion object {
        const val MAX_SCRIM_ALPHA = 0.42f
        const val DISMISS_FRACTION = 0.3f
        val VELOCITY_THRESHOLD = 96.dp
    }
}

/**
 * Modal bottom sheet for the setup wizard, modeled on the custom anchored
 * draggable sheet used by mihon (a `Dialog` + `AnchoredDraggableState` instead
 * of the stock `ModalBottomSheet`).
 *
 * Only the top drag handle can move the panel (pull down to dismiss). The
 * content area is never attached to the drag state, so dragging there only
 * scrolls its own content and can never move or dismiss the panel. Scrim tap
 * and system back still close the wizard.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val state = remember(density) { WelcomeSheetState(density) }

    val dismiss: () -> Unit = {
        scope.launch { state.hide() }
    }

    // Dismiss the wizard once the sheet actually settles on the Hidden anchor,
    // so scrim tap / back / handle pull all animate out before closing.
    LaunchedEffect(state) {
        snapshotFlow { state.anchored.settledValue }
            .filter { it == WelcomeSheetAnchor.Hidden }
            .first()
        currentOnDismissRequest.value()
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { state.updateHeight(it.height.toFloat()) },
        ) {
            // Scrim: dims with the sheet offset; tap to dismiss.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = state.scrimAlpha))
                    .clickable(onClick = dismiss),
            )

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, state.offset.roundToInt()) },
            ) {
                BackHandler(onBack = dismiss)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(BottomSheetDefaults.windowInsets),
                ) {
                    // The ONLY draggable element: the top handle. It feeds the
                    // panel's drag state so the whole sheet follows the finger.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { deltaY -> state.drag(deltaY) },
                                onDragStopped = { velocity -> state.settle(velocity) },
                            ),
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }

                    // Content is NOT connected to the drag state: scroll freely.
                    Box(modifier = Modifier.weight(1f)) {
                        content()
                    }
                }
            }
        }
    }
}
