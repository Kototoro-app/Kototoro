package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.reader.core.PagedCameraTransform
import org.skepsun.kototoro.reader.core.ZoomMode
import kotlin.math.exp
import kotlin.math.roundToInt

/** Pixel geometry and camera bounds come from reader-core; the host only owns input and rendering. */
@Composable
internal fun DesktopReaderCanvas(controller: DesktopController, state: DesktopAppState, focus: FocusRequester,
    enabled: Boolean, modifier: Modifier, controlsVisible: Boolean = true) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val canvasHeight = (maxHeight - if (controlsVisible) 44.dp else 0.dp).coerceAtLeast(1.dp)
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = with(density) { canvasHeight.roundToPx() }.coerceAtLeast(1)
        val layout = remember(state.pages, state.chapter, state.readerImages, state.readerGeometry, state.readerSettings,
            state.pageIndex, width, height) {
            DesktopReaderLayout(state.pages, state.chapter?.id ?: 0L, state.readerImages, state.readerSettings,
                state.pageIndex, width, height, state.readerGeometry)
        }
        val slot = layout.slot ?: return@BoxWithConstraints
        val loaded = slot.pageIds.all { it.value in state.readerImages }
        // Resizing preserves scale and re-clamps offsets. New page groups/fit modes start at the reading edge.
        var motion by remember(state.chapter?.id, slot.progressAnchorPageId, slot.pageIds, state.readerSettings, loaded) {
            mutableStateOf(PagedCameraTransform.initial(slot, state.readerSettings.rightToLeft))
        }
        var manipulated by remember(state.chapter?.id, slot.progressAnchorPageId, slot.pageIds, state.readerSettings, loaded) {
            mutableStateOf(false)
        }
        fun current() = if (manipulated) motion.constrained(slot)
            else PagedCameraTransform.initial(slot, state.readerSettings.rightToLeft)
        val camera = current()
        fun reset() { manipulated = false; motion = PagedCameraTransform.initial(slot, state.readerSettings.rightToLeft) }
        fun transform(factor: Float = 1f, pan: Offset = Offset.Zero, anchor: Offset? = null) {
            motion = current().transform(slot, factor, pan.x, pan.y,
                anchor?.x ?: width / 2f, anchor?.y ?: height / 2f)
            manipulated = true
        }
        Column {
            if (controlsVisible) Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("reader-fit")) {
                        Text(fitTitle(state.readerSettings.fitMode))
                    }
                    DropdownMenu(expanded && enabled, { expanded = false }) {
                        ZoomMode.entries.forEach { mode ->
                            DropdownMenuItem({ expanded = false; focus.requestFocus()
                                controller.readerSettings(state.readerSettings.copy(fitMode = mode)) },
                                modifier = Modifier.testTag("reader-fit:${mode.name}")) { Text(fitTitle(mode)) }
                        }
                    }
                }
                OutlinedButton({ transform(1f / 1.25f); focus.requestFocus() },
                    enabled = enabled && camera.scale > 1f, modifier = Modifier.testTag("reader-zoom-out")) { Text("−") }
                Text("${(camera.scale * 100).roundToInt()}%", modifier = Modifier.padding(top = 10.dp)
                    .testTag("reader-zoom"))
                OutlinedButton({ transform(1.25f); focus.requestFocus() },
                    enabled = enabled && camera.scale < PagedCameraTransform.MAX_SCALE,
                    modifier = Modifier.testTag("reader-zoom-in")) { Text("+") }
                TextButton({ reset(); focus.requestFocus() }, enabled = enabled,
                    modifier = Modifier.testTag("reader-zoom-reset")) { Text("重置视图") }
            }
            Box(Modifier.fillMaxWidth().height(canvasHeight).background(Color(0xFF15191F))
                .clipToBounds().testTag("reader-viewport")
                .onPreviewKeyEvent { event ->
                    if (!event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                        event.type != KeyEventType.KeyDown) false else {
                        val command: (() -> Unit)? = when (event.key) {
                            Key.Equals, Key.Plus -> { { transform(1.25f) } }
                            Key.Minus -> { { transform(1f / 1.25f) } }
                            Key.Zero -> { { reset() } }
                            else -> null
                        }
                        if (enabled) command?.invoke()
                        command != null
                    }
                }.focusRequester(focus).focusable()
                .pointerInput(slot, enabled) {
                    detectTapGestures(onPress = { focus.requestFocus() }, onDoubleTap = { position ->
                        if (enabled) {
                            if (current().scale > 1f) reset() else transform(2f, anchor = position)
                        }
                    })
                }.pointerInput(slot, enabled) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (enabled) { focus.requestFocus(); transform(zoom, pan, centroid) }
                    }
                }.pointerInput(slot, enabled) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!enabled || event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull { !it.isConsumed } ?: continue
                            focus.requestFocus()
                            if (event.keyboardModifiers.isCtrlPressed) {
                                transform(exp(-change.scrollDelta.y.coerceIn(-20f, 20f) * .12f), anchor = change.position)
                            } else {
                                val distance = with(density) { 48.dp.toPx() }
                                transform(pan = change.scrollDelta * -distance)
                            }
                            change.consume()
                        }
                    }
                }) {
                Layout(modifier = Modifier.fillMaxSize().graphicsLayer {
                    val current = current()
                    scaleX = current.scale; scaleY = current.scale
                    translationX = current.offsetX; translationY = current.offsetY
                }.testTag("reader-canvas"), content = {
                    slot.placements.forEach { placement ->
                        key(placement.pageId) {
                            val index = state.pages.indexOfFirst { it.id == placement.pageId.value }
                            val image = state.readerImages[placement.pageId.value]
                            val pageModifier = Modifier.testTag("reader-page:${placement.pageId.value}")
                            if (image?.tiled == true) DesktopTiledImage(image,
                                slot.visibleContentNodes(camera.scale, camera.offsetX, camera.offsetY)
                                    .firstOrNull { it.pageId == placement.pageId }, camera.scale, pageModifier)
                            else ReaderImage(image?.path, index, pageModifier)
                        }
                    }
                }) { measurables, _ ->
                    val placeables = measurables.mapIndexed { index, measurable ->
                        val bounds = slot.placements[index].boundsInSlot
                        measurable.measure(Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(1),
                            bounds.height.roundToInt().coerceAtLeast(1)))
                    }
                    // Child sizes may exceed the viewport; measuring them without coercion preserves Fit/native pixels.
                    layout(width, height) {
                        placeables.forEachIndexed { index, placeable ->
                            val bounds = slot.placements[index].boundsInSlot
                            placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                        }
                    }
                }
            }
        }
    }
}

internal fun fitTitle(mode: ZoomMode): String = when (mode) {
    ZoomMode.FIT_CENTER -> "适应整页"
    ZoomMode.FIT_WIDTH -> "适应宽度"
    ZoomMode.FIT_HEIGHT -> "适应高度"
    ZoomMode.KEEP_START -> "原始尺寸"
}
