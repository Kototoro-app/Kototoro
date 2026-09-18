package org.skepsun.kototoro.reader.render.compose

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.HorizontalReaderScene
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.TileStore

/**
 * High-performance Compose Scene Renderer for Horizontal Continuous Reader (ADR 0002).
 *
 * Implements ADR 0002:
 * 1. Restricts high-frequency scroll state consumption strictly to the Draw Phase (bypassing
 *    Composition and Layout phases entirely during touch drags and flings).
 * 2. Directly consumes [HorizontalReaderScene.resolve] to draw only intersecting pages (O(visible)).
 * 3. Reading semantics remain in the scene frame and are not recomputed by the renderer.
 */
@Composable
fun ComposeHorizontalSceneRenderer(
    scene: HorizontalReaderScene,
    modifier: Modifier = Modifier,
    initialScrollX: Float = 0f,
    scrollState: ComposeScenePrimaryScrollState = rememberComposeScenePrimaryScrollState(initialScrollX),
    placeholderColor: Color = Color.DarkGray,
    pageLabelProvider: ((PageId) -> String)? = null,
    imageColorFilter: ColorFilter? = null,
    assetProvider: (PageId) -> ImageBitmap? = { null },
    readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null,
    tileStore: TileStore? = null,
    animatedBridge: AnimatedDrawBridge? = remember { AnimatedDrawBridge() },
    onScrollProgressChanged: (scrollX: Float, maxScrollX: Float) -> Unit = { _, _ -> },
    onMotionChanged: (ViewportMotion) -> Unit = {},
    onOverScroll: ((deltaX: Float) -> Unit)? = null,
    onReleaseOverScroll: (() -> Unit)? = null,
) {
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    DisposableEffect(animatedBridge) {
        onDispose {
            animatedBridge?.stopAll()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val decaySpec = remember(density) { splineBasedDecay<Float>(density) }
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            .tileDrawBridge(tileStore)
            .animatedDrawBridge(animatedBridge)
            .onSizeChanged { size ->
                viewportWidth = size.width.toFloat()
                viewportHeight = size.height.toFloat()
                scrollState.maxOffset = (scene.totalSceneWidth - viewportWidth).coerceAtLeast(0f)
            }
            .pointerInput(scene, scrollState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val wasFlinging = scrollState.isFlinging || flingJob?.isActive == true
                    flingJob?.cancel()
                    scrollState.isFlinging = false
                    if (wasFlinging) {
                        onMotionChanged(ViewportMotion.Idle)
                        down.consume()
                    }

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var lastPointerX = down.position.x
                    var lastPointerY = down.position.y
                    var lastUptimeMillis = down.uptimeMillis
                    var dragStarted = false
                    val touchSlop = viewConfiguration.touchSlop

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.any { it.isConsumed } && !dragStarted) {
                            break
                        }
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        val currentPointerX = change.position.x
                        val currentPointerY = change.position.y
                        val deltaX = lastPointerX - currentPointerX
                        lastPointerX = currentPointerX
                        lastPointerY = currentPointerY

                        val currentUptimeMillis = change.uptimeMillis
                        val dtMillis = (currentUptimeMillis - lastUptimeMillis).coerceAtLeast(1L)
                        lastUptimeMillis = currentUptimeMillis

                        if (!dragStarted) {
                            val totalDisplacementX = kotlin.math.abs(down.position.x - currentPointerX)
                            val totalDisplacementY = kotlin.math.abs(down.position.y - currentPointerY)
                            if (totalDisplacementX > touchSlop || totalDisplacementY > touchSlop) {
                                dragStarted = true
                                scrollState.isDragging = true
                                onMotionChanged(
                                    ViewportMotion(
                                        isDragging = true,
                                        timestampNanos = change.uptimeMillis * 1_000_000L,
                                    ),
                                )
                            }
                        }

                        if (dragStarted) {
                            change.consume()
                            val instantVelocityX = (deltaX * 1000f) / dtMillis

                            val maxScroll = (scene.totalSceneWidth - viewportWidth).coerceAtLeast(0f)
                            scrollState.maxOffset = maxScroll

                            val desiredScroll = scrollState.offset + deltaX
                            val newScroll = desiredScroll.coerceIn(0f, maxScroll)
                            val overscrollDelta = desiredScroll - newScroll
                            if (overscrollDelta != 0f && onOverScroll != null) {
                                onOverScroll(overscrollDelta)
                            }

                            val motion = ViewportMotion(
                                velocityX = instantVelocityX,
                                velocityY = 0f,
                                isDragging = true,
                                timestampNanos = change.uptimeMillis * 1_000_000L,
                            )
                            scrollState.snapTo(newScroll)
                            onMotionChanged(motion)
                            onScrollProgressChanged(newScroll, maxScroll)
                        }
                    } while (event.changes.any { it.pressed })

                    onReleaseOverScroll?.invoke()

                    if (dragStarted) {
                        scrollState.isDragging = false
                        val velocity = velocityTracker.calculateVelocity()
                        val initialVelocityX = -velocity.x

                        if (kotlin.math.abs(initialVelocityX) > 100f) {
                            scrollState.isFlinging = true
                            onMotionChanged(
                                ViewportMotion(
                                    velocityX = initialVelocityX,
                                    velocityY = 0f,
                                    isDragging = false,
                                    timestampNanos = System.nanoTime(),
                                ),
                            )
                            flingJob = coroutineScope.launch {
                                try {
                                    val animState = AnimationState(
                                        initialValue = scrollState.offset,
                                        initialVelocity = initialVelocityX,
                                    )
                                    var lastAnimatedX = scrollState.offset
                                    animState.animateDecay(decaySpec) {
                                        val frameDelta = value - lastAnimatedX
                                        lastAnimatedX = value
                                        val maxScroll = (scene.totalSceneWidth - viewportWidth).coerceAtLeast(0f)
                                        scrollState.maxOffset = maxScroll
                                        val clamped = (scrollState.offset + frameDelta).coerceIn(0f, maxScroll)
                                        val motion = ViewportMotion(
                                            velocityX = this.velocity,
                                            velocityY = 0f,
                                            isDragging = false,
                                            timestampNanos = System.nanoTime(),
                                        )
                                        scrollState.snapTo(clamped)
                                        onMotionChanged(motion)
                                        onScrollProgressChanged(clamped, maxScroll)
                                        if (clamped <= 0f || clamped >= maxScroll) {
                                            cancelAnimation()
                                        }
                                    }
                                } catch (_: CancellationException) {
                                    // Fling was interrupted by a new touch down
                                } finally {
                                    scrollState.isFlinging = false
                                    onMotionChanged(ViewportMotion.Idle)
                                }
                            }
                        } else {
                            onMotionChanged(ViewportMotion.Idle)
                        }
                    } else {
                        scrollState.isDragging = false
                        onMotionChanged(ViewportMotion.Idle)
                    }
                }
            }
            .drawWithContent {
                // DRAW PHASE: Read scrollState.offset here.
                // Modifying scrollState schedules a redraw without triggering Composition or Layout!
                val currentX = scrollState.offset
                val vWidth = size.width
                val vHeight = size.height

                if (vWidth > 0f && vHeight > 0f) {
                    val verticalOffset = ((vHeight - scene.availableHeight) / 2f).coerceAtLeast(0f)
                    val viewport = ReaderViewport(
                        bounds = FloatRect.fromLtwh(currentX, 0f, vWidth, scene.availableHeight.toFloat()),
                    )
                    val frame = scene.resolve(viewport)
                    val seamPolicy = PageSeamPolicy.forScene(scene.pageSpacingPx, isVertical = false)
                    drawFrameNodes(
                        frame = frame,
                        viewportScrollX = currentX,
                        viewportScrollY = 0f,
                        horizontalOffset = 0f,
                        verticalOffset = verticalOffset,
                        seamPolicy = seamPolicy,
                        placeholderColor = placeholderColor,
                        pageLabelProvider = pageLabelProvider,
                        textMeasurer = textMeasurer,
                        imageColorFilter = imageColorFilter,
                        assetProvider = assetProvider,
                        readerAssetProvider = readerAssetProvider,
                        animatedBridge = animatedBridge,
                    )
                }

                drawContent()
            },
    )
}
