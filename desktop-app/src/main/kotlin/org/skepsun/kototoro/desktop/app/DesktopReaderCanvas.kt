package org.skepsun.kototoro.desktop.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.PagedCameraTransform
import org.skepsun.kototoro.reader.core.PagedDragState
import org.skepsun.kototoro.reader.core.PagedMotionSnapshot
import org.skepsun.kototoro.reader.core.PagedSlot
import org.skepsun.kototoro.reader.core.PagedTransitionResolver
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.render.compose.ScenePageTransition
import org.skepsun.kototoro.reader.render.compose.ScenePageTransitionRenderer
import org.skepsun.kototoro.reader.render.compose.resolveScenePageTransition
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderPageTransform
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderSimulationPageShadow
import org.skepsun.kototoro.reader.ui.compose.composeReaderPageCurl
import org.skepsun.kototoro.reader.ui.compose.readerTapGestures
import org.skepsun.kototoro.reader.ui.compose.rememberComposeReaderPageCurlState
import org.skepsun.kototoro.reader.ui.compose.trackComposeReaderPageCurl
import org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig
import org.skepsun.kototoro.reader.ui.compose.design.ReaderZoomControls
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The paged reader, as Android's scene pager: the current slot and its neighbours sit on one strip moved by a turn
 * offset (in slots). Dragging hands over between panning a zoomed page and turning ([PagedDragState]), releases snap
 * with reader-core's thresholds, and every turn (keys, buttons, taps, wheel) animates with Android's transition
 * styles (slide, cover, paper curl) from the shared renderer. Geometry and camera bounds come from reader-core.
 */
@Composable
internal fun DesktopReaderCanvas(controller: DesktopController, state: DesktopAppState, focus: FocusRequester,
    enabled: Boolean, modifier: Modifier, controlsVisible: Boolean = true, onToggleControls: () -> Unit = {},
    autoScroll: DesktopReaderAutoScroll = remember { DesktopReaderAutoScroll() }, onShowMenu: () -> Unit = {}) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // The reader chrome floats over the page (Android's layout), so the canvas takes the whole height.
        val canvasHeight = maxHeight.coerceAtLeast(1.dp)
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = with(density) { canvasHeight.roundToPx() }.coerceAtLeast(1)
        val settings = state.readerSettings
        val layout = remember(state.pages, state.chapter, state.readerImages, state.readerGeometry, settings,
            state.pageIndex, width, height) {
            DesktopReaderLayout(state.pages, state.chapter?.id ?: 0L, state.readerImages, settings,
                state.pageIndex, width, height, state.readerGeometry)
        }
        val slot = layout.slot ?: return@BoxWithConstraints
        val slotIndex = layout.slotIndex
        val vertical = settings.vertical
        val rightToLeft = settings.rightToLeft && !vertical
        val direction = settings.readingDirection
        // The turning axis: page width for horizontal reading, height for Android's vertical mode.
        val extent = if (vertical) height else width
        val physicalSign = if (rightToLeft) -1f else 1f
        val transition = resolveScenePageTransition(settings.animation)
        val animated = settings.animation != ReaderAnimation.NONE
        val loaded = slot.pageIds.all { it.value in state.readerImages }
        val scope = rememberCoroutineScope()

        // Turn offset in slots relative to the current slot: positive while moving forward.
        val travel = remember(state.chapter?.id) { Animatable(0f) }
        var settledSlot by remember(state.chapter?.id) { mutableIntStateOf(slotIndex) }
        var dragging by remember { mutableStateOf(false) }
        var committedByDrag by remember { mutableStateOf(false) }
        LaunchedEffect(slotIndex) {
            val delta = slotIndex - settledSlot
            if (committedByDrag || delta == 0 || abs(delta) > 1 || !animated) {
                committedByDrag = false
                travel.snapTo(0f)
                settledSlot = slotIndex
            } else {
                // A turn from keys, buttons or taps: start where the previous slot rested and slide into place.
                travel.snapTo(-delta.toFloat())
                travel.animateTo(0f, tween(TURN_MILLIS))
                settledSlot = slotIndex
            }
        }

        // Resizing preserves scale and re-clamps offsets. New page groups/fit modes start at the reading edge.
        var motion by remember(state.chapter?.id, slot.progressAnchorPageId, slot.pageIds, settings, loaded) {
            mutableStateOf(PagedCameraTransform.initial(slot, rightToLeft))
        }
        var manipulated by remember(state.chapter?.id, slot.progressAnchorPageId, slot.pageIds, settings, loaded) {
            mutableStateOf(false)
        }
        fun current() = if (manipulated) motion.constrained(slot) else PagedCameraTransform.initial(slot, rightToLeft)
        val camera = current()
        fun reset() { manipulated = false; motion = PagedCameraTransform.initial(slot, rightToLeft) }
        fun transform(factor: Float = 1f, pan: Offset = Offset.Zero, anchor: Offset? = null) {
            motion = current().transform(slot, factor, pan.x, pan.y, anchor?.x ?: width / 2f, anchor?.y ?: height / 2f)
            manipulated = true
        }
        /** The pan range of the current camera along the turning axis: how far a drag moves the page before it turns. */
        fun panRange(): ClosedFloatingPointRange<Float> {
            val base = current()
            val far = 1e7f
            val low = base.transform(slot, 1f, if (vertical) 0f else -far, if (vertical) -far else 0f, width / 2f, height / 2f)
            val high = base.transform(slot, 1f, if (vertical) 0f else far, if (vertical) far else 0f, width / 2f, height / 2f)
            val a = if (vertical) low.offsetY else low.offsetX
            val b = if (vertical) high.offsetY else high.offsetX
            return minOf(a, b)..maxOf(a, b)
        }
        fun axisOffset(camera: PagedCameraTransform) = if (vertical) camera.offsetY else camera.offsetX

        /** Animates a turn to the neighbour [step] slots away (±1), then commits it; a boundary hands over to the controller. */
        fun turn(forward: Boolean) {
            if (!enabled) return
            val target = layout.turnIndex(forward)
            if (target == null || !animated) { controller.turnPage(forward); return }
            scope.launch {
                travel.animateTo(if (forward) 1f else -1f, tween(TURN_MILLIS))
                committedByDrag = true
                controller.page(target)
            }
        }

        val tapHandlers = rememberTapGridHandlers(focus, autoScroll) { area, long ->
            if (enabled) performTapAction(TapGridConfig.action(settings.tapGrid, area, long), ::turn, controller,
                onToggleControls, onShowMenu)
        }

        // Android's auto scroll in paged mode: a page larger than the view scrolls a dp at a time; once it cannot move
        // (or fits), the page turns after the shared page-switch delay. Interactions pause it.
        LaunchedEffect(autoScroll.active, settings.autoScrollSpeed, slotIndex, enabled) {
            if (!autoScroll.active || !enabled) return@LaunchedEffect
            val step = with(density) { 1.dp.toPx() }
            val scrollDelay = org.skepsun.kototoro.reader.core.ReaderAutoScroll.scrollDelayMs(settings.autoScrollSpeed)
            val switchDelay = org.skepsun.kototoro.reader.core.ReaderAutoScroll.pageSwitchDelayMs(settings.autoScrollSpeed)
            var waited = 0L
            while (true) {
                kotlinx.coroutines.delay(scrollDelay)
                if (autoScroll.isPaused || dragging || travel.isRunning) continue
                val before = current()
                transform(pan = if (vertical) Offset(0f, -step) else Offset(0f, -step))
                val after = current()
                if (abs(after.offsetY - before.offsetY) >= .5f) continue
                waited += scrollDelay
                if (waited >= switchDelay) { waited = 0L; turn(true) }
            }
        }

        val curlState = rememberComposeReaderPageCurlState()
        Box {
            Box(Modifier.fillMaxWidth().height(canvasHeight).background(LocalDesktopReaderPageStyle.current.background)
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
                .trackComposeReaderPageCurl(curlState, transition == ScenePageTransition.CURL)
                // Android's reader actions per tap-grid area; a long press or a right click runs the long-tap action.
                .readerTapGestures(tapHandlers.interaction, tapHandlers.tap, tapHandlers.longTap,
                    with(density) { 48.dp.toPx() }, tapHandlers.longTap)
                .pointerInput(slot, enabled) {
                    detectTapGestures(onDoubleTap = { position ->
                        if (enabled) { if (current().scale > 1f) reset() else transform(2f, anchor = position) }
                    })
                }
                .pointerInput(slot, slotIndex, enabled, layout) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabled) return@awaitEachGesture
                        scope.launch { travel.stop() }
                        val dragState = PagedDragState(direction)
                        val velocity = VelocityTracker()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        var turned = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size > 1) {
                                // Pinch: zoom around the centroid, never a turn.
                                transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid())
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = pressed.first()
                            val delta = change.positionChange()
                            if (delta == Offset.Zero) continue
                            velocity.addPosition(change.uptimeMillis, change.position)
                            val axisDelta = if (vertical) delta.y else delta.x
                            val movement = dragState.dragBy(axisDelta, axisOffset(current()), panRange())
                            val axisPan = movement.contentOffset - axisOffset(current())
                            val pan = if (vertical) Offset(delta.x, axisPan) else Offset(axisPan, delta.y)
                            if (pan != Offset.Zero) transform(pan = pan)
                            if (movement.pageDelta != 0f) {
                                dragging = true; turned = true
                                scope.launch { travel.snapTo((travel.value + movement.pageDelta / extent).coerceIn(-1f, 1f)) }
                            }
                            change.consume()
                        }
                        dragging = false
                        if (!turned) return@awaitEachGesture
                        val released = velocity.calculateVelocity()
                        val forwardVelocity = if (vertical) -released.y else released.x * if (rightToLeft) 1f else -1f
                        val target = dragState.resolveTargetSlot(slotIndex, extent.toFloat(), forwardVelocity,
                            layout.scene.slotCount)
                        scope.launch {
                            if (target == slotIndex) {
                                val pull = travel.value
                                travel.animateTo(0f, tween(TURN_MILLIS / 2))
                                // Dragging past the chapter's first/last page continues into the adjacent chapter.
                                if (abs(pull) >= .2f && layout.turnIndex(pull > 0) == null) controller.turnPage(pull > 0)
                            } else {
                                travel.animateTo(if (target > slotIndex) 1f else -1f, tween(TURN_MILLIS / 2))
                                committedByDrag = true
                                layout.scene.allSlots[target].progressAnchorPageId
                                    ?.let(layout.scene::indexOf)?.let(controller::page)
                            }
                        }
                    }
                }
                .pointerInput(slot, enabled, layout) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!enabled || event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull { !it.isConsumed } ?: continue
                            focus.requestFocus()
                            autoScroll.interacted()
                            if (event.keyboardModifiers.isCtrlPressed) {
                                transform(exp(-change.scrollDelta.y.coerceIn(-20f, 20f) * .12f), anchor = change.position)
                            } else {
                                // Scroll a page that overflows; at its edge (or when it fits) the wheel turns pages.
                                val before = current()
                                transform(pan = change.scrollDelta * -with(density) { 48.dp.toPx() })
                                val after = current()
                                // Sub-pixel differences between the resting and the clamped camera are no scroll.
                                if (abs(after.offsetX - before.offsetX) < 1f && abs(after.offsetY - before.offsetY) < 1f &&
                                    change.scrollDelta.y != 0f && !travel.isRunning) turn(change.scrollDelta.y > 0)
                            }
                            change.consume()
                        }
                    }
                }) {
                val snapshot = PagedMotionSnapshot(currentSlot = slotIndex, settledSlot = settledSlot,
                    targetSlot = slotIndex, offsetFraction = travel.value,
                    isScrollInProgress = dragging || travel.isRunning)
                val travelFraction = slotIndex - settledSlot + travel.value
                val inFlight = abs(travelFraction) > ScenePageTransitionRenderer.COVER_PROGRESS_EPSILON || snapshot.isScrollInProgress
                val neighbours = (-1..1).mapNotNull { step -> layout.scene.allSlots.getOrNull(slotIndex + step) }
                for (strip in neighbours) {
                    val input = PagedTransitionResolver.resolve(snapshot, strip.slotIndex, direction,
                        isCurlUnfolding = travel.value < 0f)
                    val transform = ScenePageTransitionRenderer.transformFor(transition, input, vertical, rightToLeft)
                    // Base strip position: forward slots lie in the reading direction (below, in vertical mode).
                    val base = (strip.slotIndex - slotIndex - travel.value) * extent * physicalSign
                    if (abs(base) >= extent * 1.5f && transition == ScenePageTransition.SLIDE) continue
                    val shift = when (transition) {
                        ScenePageTransition.SLIDE -> 0f
                        ScenePageTransition.CURL -> transform.translationFactor * extent * physicalSign
                        ScenePageTransition.COVER -> ScenePageTransitionRenderer.resolveSceneCoverPinShift(
                            strip.slotIndex, settledSlot, travelFraction, base, inFlight)
                    }
                    val legacy = ComposeReaderPageTransform(alpha = transform.alpha, zIndex = transform.zIndex,
                        foldProgress = transform.foldProgress, isCurlUnfolding = transform.isCurlUnfolding,
                        revealedPageShade = transform.revealedPageShade)
                    key(strip.slotIndex) {
                        Box(Modifier.fillMaxSize().zIndex(transform.zIndex)
                            .graphicsLayer {
                                if (vertical) translationY = base + shift else translationX = base + shift
                                alpha = transform.alpha
                            }
                            // Each slot clips its own pages (as Android clips slot rects): a page overflowing a
                            // neighbouring slot (wide pages, native size) must never draw over the current one.
                            .clipToBounds()
                            .background(LocalDesktopReaderPageStyle.current.background)
                            .composeReaderPageCurl(legacy, isVertical = vertical, isReadingReversed = rightToLeft, curlState)) {
                            DesktopReaderSlot(state, strip, if (strip.slotIndex == slotIndex) camera else
                                PagedCameraTransform.initial(strip, rightToLeft), width, height)
                            if (transform.revealedPageShade > 0f) ComposeReaderSimulationPageShadow(legacy)
                        }
                    }
                }
                // Android's page-number overlay.
                if (settings.pageNumbers) {
                    val shown = layout.indices
                    val label = if (shown.size > 1) "${shown.first() + 1}–${shown.last() + 1} / ${state.pages.size}"
                        else "${state.pageIndex + 1} / ${state.pages.size}"
                    Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter)
                        .zIndex(10f).padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = .55f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp).testTag("reader-page-number"))
                }
            }
            // Android's zoom buttons; the current zoom between them resets the view.
            // These controls sit on the reader background rather than a themed surface.
            if (controlsVisible) CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides LocalDesktopReaderPageStyle.current.text,
            ) {
                ReaderZoomControls(
                    onZoomIn = { transform(1.25f); focus.requestFocus() },
                    onZoomOut = { transform(1f / 1.25f); focus.requestFocus() },
                    zoomInDescription = AndroidStrings["zoom_in"],
                    zoomOutDescription = AndroidStrings["zoom_out"],
                    zoomInEnabled = enabled && camera.scale < PagedCameraTransform.MAX_SCALE,
                    zoomOutEnabled = enabled && camera.scale > 1f,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    androidx.compose.material3.Text("${(camera.scale * 100).roundToInt()}%",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(enabled = enabled) { reset(); focus.requestFocus() }
                            .padding(horizontal = 8.dp, vertical = 6.dp).testTag("reader-zoom"))
                }
            }
        }
    }
}

/** One slot's pages at their scene placements under [camera]; tiles only where the camera shows them. */
@Composable
private fun DesktopReaderSlot(state: DesktopAppState, slot: PagedSlot, camera: PagedCameraTransform, width: Int, height: Int) {
    Layout(modifier = Modifier.fillMaxSize().graphicsLayer {
        scaleX = camera.scale; scaleY = camera.scale
        translationX = camera.offsetX; translationY = camera.offsetY
    }.testTag("reader-canvas:${slot.slotIndex}"), content = {
        slot.placements.forEach { placement ->
            key(placement.pageId) {
                val index = state.pages.indexOfFirst { it.id == placement.pageId.value }
                val image = state.readerImages[placement.pageId.value]
                val pageModifier = Modifier.testTag("reader-page:${placement.pageId.value}")
                if (image?.tiled == true) DesktopTiledImage(image,
                    slot.visibleContentNodes(camera.scale, camera.offsetX, camera.offsetY)
                        .firstOrNull { it.pageId == placement.pageId }, camera.scale, pageModifier)
                else ReaderImage(image, index, pageModifier)
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

/** Android's page-turn duration. */
private const val TURN_MILLIS = 280

internal fun fitTitle(mode: ZoomMode): String = when (mode) {
    ZoomMode.FIT_CENTER -> "适应整页"
    ZoomMode.FIT_WIDTH -> "适应宽度"
    ZoomMode.FIT_HEIGHT -> "适应高度"
    ZoomMode.KEEP_START -> "原始尺寸"
}
