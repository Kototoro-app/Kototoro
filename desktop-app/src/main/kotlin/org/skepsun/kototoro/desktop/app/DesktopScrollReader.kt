package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import org.skepsun.kototoro.reader.ui.compose.readerTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Virtualized full-size items and viewport semantics share one scene: [VerticalReaderScene] for Android's webtoon mode
 * and [org.skepsun.kototoro.reader.core.HorizontalReaderScene] for its continuous horizontal mode, which only differ in
 * the axis the strip runs along and the direction it is read in.
 */
@Composable
internal fun DesktopScrollReader(controller: DesktopController, state: DesktopAppState, focus: FocusRequester,
    closing: Boolean, modifier: Modifier, onToggleControls: () -> Unit = {},
    autoScroll: DesktopReaderAutoScroll = remember { DesktopReaderAutoScroll() }, onShowMenu: () -> Unit = {},
    horizontal: Boolean = false, reversed: Boolean = false) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewportHeight = maxHeight.coerceAtLeast(1.dp)
        // One gutter for the scrollbar: at the end of the horizontal strip it takes height, at the side of the vertical
        // one it takes width. The scene has to see the same box the list draws into, or the anchors drift from the pages.
        val gutter = with(density) { 16.dp.roundToPx() }
        val width = (constraints.maxWidth - if (horizontal) 0 else gutter).coerceAtLeast(1)
        val height = (with(density) { viewportHeight.roundToPx() } - if (horizontal) gutter else 0).coerceAtLeast(1)
        val forwardExtent = if (horizontal) width.toFloat() else height.toFloat()
        // Android's `webtoon_gaps`: pages stack flush by default and gain the webtoon gap when the switch is on. The
        // scene spacing and the list arrangement must stay the same value, or the anchors drift from what is drawn.
        // The horizontal strip has no such switch on Android, so its pages stay flush.
        val gap = if (!horizontal && state.readerSettings.webtoonGaps) WEBTOON_PAGE_GAP_DP.dp else 0.dp
        val spacing = with(density) { gap.roundToPx() }
        val layout: DesktopStripLayout = remember(state.pages, state.readerImages, state.readerGeometry, width, height,
            spacing, horizontal, reversed) {
            if (horizontal) DesktopHorizontalScrollLayout(state.pages, state.readerImages, width, height, spacing,
                reversed, state.readerGeometry)
            else DesktopScrollLayout(state.pages, state.readerImages, width, height, spacing, state.readerGeometry)
        }
        val chapterId = state.chapter?.id ?: return@BoxWithConstraints
        val list = remember(chapterId) { LazyListState(state.pageIndex.coerceIn(state.pages.indices), 0) }
        val navigation = remember(chapterId, state.readerNavigation) { state.readerTargetPage to state.readerTargetScroll }
        val target = remember(chapterId, state.readerNavigation, width) {
            if (state.readerScrollReady) state.pageIndex to state.readerScroll else navigation
        }
        var restored by remember(chapterId, state.readerNavigation, width) { mutableStateOf(false) }
        val targetLoaded = state.pages.getOrNull(target.first)?.id in state.readerImages
        val currentState by rememberUpdatedState(state)
        val currentLayout by rememberUpdatedState(layout)
        val scope = rememberCoroutineScope()
        var boundaryPending by remember(chapterId, state.readerNavigation) { mutableStateOf(false) }
        val boundaryEvent by rememberUpdatedState<(Boolean) -> Boolean> { forward ->
            val snapshot = currentState
            if (closing || !restored || boundaryPending || snapshot.busy || snapshot.error != null ||
                !snapshot.readerScrollReady || !snapshot.readerSettings.automaticChapter ||
                snapshot.adjacentChapter(forward) == null) false else {
                boundaryPending = true
                scope.launch {
                    try { controller.continuousChapter(forward, chapterId, snapshot.readerNavigation).join() }
                    finally { boundaryPending = false }
                }
                true
            }
        }
        // A finger or a wheel moves the strip along its axis; the list state itself counts a positive delta as "forward",
        // and a right-to-left strip is laid out the other way round, so the drag that reveals the next page swaps sides.
        fun axisOf(offset: Offset): Float = if (horizontal) offset.x else offset.y
        val boundaryConnection = remember(list, horizontal) { object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || axisOf(consumed) != 0f || axisOf(available) == 0f) return Offset.Zero
                val forward = axisOf(available) < 0f
                val atBoundary = if (forward) !list.canScrollForward else !list.canScrollBackward
                return if (atBoundary && boundaryEvent(forward)) available else Offset.Zero
            }
        } }
        suspend fun scroll(delta: Float) {
            val forward = delta > 0f
            if ((if (forward) !list.canScrollForward else !list.canScrollBackward) && boundaryEvent(forward)) return
            list.scrollBy(delta)
        }
        val fling = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
        // Android's auto scroll: the strip moves a dp per shared scroll delay; at the chapter end it hands over to the
        // automatic chapter turn. Interactions pause it.
        LaunchedEffect(autoScroll.active, state.readerSettings.autoScrollSpeed, chapterId, restored, closing) {
            if (!autoScroll.active || !restored || closing) return@LaunchedEffect
            val step = with(density) { 1.dp.toPx() }
            val delayMs = org.skepsun.kototoro.reader.core.ReaderAutoScroll.scrollDelayMs(state.readerSettings.autoScrollSpeed)
            while (true) {
                kotlinx.coroutines.delay(delayMs)
                if (autoScroll.isPaused || list.isScrollInProgress) continue
                if (!list.canScrollForward) { boundaryEvent(true); continue }
                list.scrollBy(step)
            }
        }
        val visibleFrame by remember(layout, list) { derivedStateOf {
            layout.frame(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        } }

        LaunchedEffect(chapterId, state.readerNavigation, width, targetLoaded, state.busy) {
            if (state.busy || restored) return@LaunchedEffect
            controller.continuousImages(chapterId, listOf(target.first)).join()
            if (state.pages[target.first].id !in controller.state.value.readerImages) return@LaunchedEffect
            val pageExtent = currentLayout.extent(target.first).roundToInt()
            list.scrollToItem(target.first, target.second.roundToInt().coerceIn(0, (pageExtent - 1).coerceAtLeast(0)))
            restored = true
            focus.requestFocus()
        }
        val prediction = remember { org.skepsun.kototoro.reader.core.ReaderPrediction() }
        LaunchedEffect(chapterId, state.readerNavigation, width, horizontal) {
            snapshotFlow {
                val info = list.layoutInfo
                val geometry = currentLayout
                val snapshot = currentState
                val indices = info.visibleItemsInfo.map { it.index }.filter { it in snapshot.pages.indices }
                val frame = geometry.frame(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                val first = frame.progress.activePageId?.let(geometry.scene::indexOf) ?: -1
                val last = frame.progress.lastVisiblePageId?.let(geometry.scene::indexOf) ?: -1
                val matched = info.visibleItemsInfo.all { item -> geometry.extent(item.index).roundToInt() == item.size }
                ObservedViewport(indices, first, frame.progress.intraPageOffsetPx, last, !list.canScrollForward,
                    restored && matched && !snapshot.busy,
                    indices.all { snapshot.pages[it].id in snapshot.readerImages &&
                        snapshot.pages[it].id !in snapshot.readerFailedPages })
            }.distinctUntilChanged().collect { viewport ->
                if (viewport.stable && viewport.indices.isNotEmpty()) {
                    controller.continuousImages(chapterId, viewport.indices)
                    // Android's continuous prediction (reader-core): pages within the lookahead load in the background.
                    val geometry = currentLayout
                    val frame = geometry.frame(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                    controller.prefetchPages(chapterId, prediction.predict(geometry.scene, frame)
                        .filter { it.priority != org.skepsun.kototoro.reader.core.PrefetchPriority.IMMEDIATE }
                        .map { geometry.scene.indexOf(it.pageId) }.filter { it >= 0 && it !in viewport.indices })
                    if (viewport.loaded) controller.continuousProgress(chapterId, viewport.first, viewport.offset,
                        viewport.last, viewport.atEnd)
                }
            }
        }
        val tapHandlers = rememberTapGridHandlers(focus, autoScroll) { area, long ->
            if (!closing && restored) performTapAction(
                org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig.action(state.readerSettings.tapGrid, area, long),
                { forward -> scope.launch { list.animateScrollBy(forwardExtent * if (forward) .9f else -.9f) } },
                controller, onToggleControls, onShowMenu)
        }
        val pageContent: LazyListScope.() -> Unit = {
            itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                val extent = with(density) { layout.extent(index).toDp() }
                val item = (if (horizontal) Modifier.fillMaxHeight().width(extent) else Modifier.fillMaxWidth().height(extent))
                    .testTag("reader-page:${page.id}")
                if (page.id in state.readerFailedPages && page.id !in state.readerImages) {
                    Box(item, contentAlignment = Alignment.Center) {
                        TextButton({ controller.reloadPage() }, enabled = page.id !in state.readerLoading,
                            modifier = Modifier.testTag("reader-page-retry:${page.id}")) {
                            Text(desktopText("reader.pageLoadRetry"))
                        }
                    }
                } else {
                    val image = state.readerImages[page.id]
                    if (image?.tiled == true) DesktopTiledImage(image,
                        visibleFrame.visibleNodes.firstOrNull { it.pageId.value == page.id }, modifier = item)
                    else ReaderImage(image, index, item)
                }
            }
        }
        Column {
            Box(Modifier.fillMaxWidth().height(viewportHeight).background(LocalDesktopReaderPageStyle.current.background).clipToBounds()
                .nestedScroll(boundaryConnection)
                .pointerInput(list, horizontal) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            autoScroll.interacted()
                            val change = event.changes.firstOrNull { !it.isConsumed } ?: continue
                            // A mouse only has a vertical wheel, so it moves the horizontal strip too; a touchpad's
                            // sideways swipe adds to it.
                            val delta = if (horizontal) change.scrollDelta.y + change.scrollDelta.x else change.scrollDelta.y
                            if (delta == 0f) continue
                            val forward = delta > 0f
                            val atBoundary = if (forward) !list.canScrollForward else !list.canScrollBackward
                            if (!atBoundary) {
                                // The vertical list already scrolls on the wheel; a horizontal one only moves with a
                                // tilted wheel or a touchpad swipe, which no reader has to hand, so the wheel is mapped
                                // onto the strip's own axis here.
                                if (horizontal) {
                                    change.consume()
                                    val step = with(density) { 48.dp.toPx() } * delta
                                    scope.launch { scroll(step) }
                                }
                            } else if (boundaryEvent(forward)) {
                                change.consume()
                            }
                        }
                    }
                }
                .testTag("reader-viewport").focusRequester(focus).focusable().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                        false
                    } else {
                        val step = with(density) { 48.dp.toPx() }
                        val command: (suspend () -> Unit)? = when (event.key) {
                            Key.DirectionDown -> { { scroll(step) } }
                            Key.DirectionUp -> { { scroll(-step) } }
                            Key.DirectionRight -> if (horizontal) { { scroll(forwardExtent * .9f) } } else null
                            Key.DirectionLeft -> if (horizontal) { { scroll(-forwardExtent * .9f) } } else null
                            Key.PageDown -> { { scroll(forwardExtent * .9f) } }
                            Key.PageUp -> { { scroll(-forwardExtent * .9f) } }
                            Key.Spacebar -> { { scroll(forwardExtent * if (event.isShiftPressed) -.9f else .9f) } }
                            Key.MoveHome -> { { controller.page(0).join() } }
                            Key.MoveEnd -> { { controller.page(state.pages.lastIndex).join() } }
                            else -> null
                        }
                        if (!closing && command != null && (restored || event.key == Key.MoveHome || event.key == Key.MoveEnd)) {
                            scope.launch { command() }
                        }
                        command != null
                    }
                }.pointerInput(focus) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); focus.requestFocus() }
                }
                // Android's reader actions on the webtoon reader: page actions scroll a screen.
                .readerTapGestures(tapHandlers.interaction, tapHandlers.tap, tapHandlers.longTap,
                    with(density) { 48.dp.toPx() }, tapHandlers.longTap)
                // The list scrolls under touch already; a mouse drag scrolls it too, and a flick keeps it moving.
                .pointerInput(list, restored, closing, horizontal, reversed) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type != androidx.compose.ui.input.pointer.PointerType.Mouse || !restored || closing) return@awaitEachGesture
                        val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        var dragged = false
                        var travelled = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // Positive is "toward the next page" for both strips: up for the vertical one, and toward
                            // the reading direction — left to right, or right to left — for the horizontal one.
                            val advance = if (horizontal) {
                                (change.position.x - change.previousPosition.x) * (if (reversed) 1f else -1f)
                            } else {
                                -(change.position.y - change.previousPosition.y)
                            }
                            travelled += advance
                            if (!dragged && kotlin.math.abs(travelled) < viewConfiguration.touchSlop) continue
                            dragged = true
                            velocity.addPosition(change.uptimeMillis, change.position)
                            list.dispatchRawDelta(advance)
                            change.consume()
                        }
                        if (!dragged) return@awaitEachGesture
                        val raw = velocity.calculateVelocity()
                        val speed = if (horizontal) raw.x * (if (reversed) 1f else -1f) else -raw.y
                        val forward = travelled > 0f
                        if ((if (forward) !list.canScrollForward else !list.canScrollBackward) && boundaryEvent(forward)) {
                            return@awaitEachGesture
                        }
                        scope.launch { list.scroll { with(fling) { performFling(speed) } } }
                    }
                }) {
                if (horizontal) {
                    LazyRow(state = list, userScrollEnabled = !closing && restored, reverseLayout = reversed,
                        horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxSize()
                            .padding(bottom = 16.dp).testTag("reader-scroll-list"), content = pageContent)
                } else {
                    LazyColumn(state = list, userScrollEnabled = !closing && restored,
                        verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxSize()
                            .padding(end = 16.dp).testTag("reader-scroll-list"), content = pageContent)
                }
                if (restored) {
                    if (horizontal) {
                        HorizontalScrollbar(adapter = rememberScrollbarAdapter(list),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("reader-scrollbar"))
                    } else {
                        VerticalScrollbar(adapter = rememberScrollbarAdapter(list),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("reader-scrollbar"))
                    }
                }
            }
        }
    }
}

private data class ObservedViewport(val indices: List<Int>, val first: Int, val offset: Float, val last: Int,
    val atEnd: Boolean, val stable: Boolean, val loaded: Boolean)
