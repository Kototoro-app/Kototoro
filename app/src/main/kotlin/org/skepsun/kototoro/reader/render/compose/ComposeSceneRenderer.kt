package org.skepsun.kototoro.reader.render.compose

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import kotlin.math.roundToInt

/**
 * Candidate A (Strategic Primary): High-performance Compose Scene Renderer for Webtoon.
 *
 * Implements ADR 0002:
 * 1. Restricts high-frequency scroll state consumption strictly to the Draw Phase (bypassing
 *    Composition and Layout phases entirely during touch drags and flings).
 * 2. Directly consumes [VerticalReaderScene.resolve] to draw only intersecting pages (O(visible)).
 * 3. Low-frequency semantic state (active page) is emitted via [onActivePageChanged] without
 *    triggering recomposition of the rendering viewport.
 */
@Composable
fun ComposeSceneRenderer(
    scene: VerticalReaderScene,
    modifier: Modifier = Modifier,
    initialScrollY: Float = 0f,
    placeholderColor: Color = Color.DarkGray,
    assetProvider: (PageId) -> ImageBitmap? = { null },
    readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null,
    onActivePageChanged: (PageId) -> Unit = {},
    onScrollProgressChanged: (scrollY: Float, maxScrollY: Float) -> Unit = { _, _ -> },
    onMotionChanged: (ViewportMotion) -> Unit = {},
) {
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    // High-frequency scroll offset. Read ONLY in the Draw Phase to bypass Composition/Layout!
    val scrollYState = remember { mutableFloatStateOf(initialScrollY) }
    var isFlinging by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val decaySpec = remember { FloatExponentialDecaySpec().generateDecayAnimationSpec<Float>() }

    // Low-frequency active page observer (fires only when active page index changes)
    LaunchedEffect(scene) {
        snapshotFlow {
            if (viewportWidth <= 0f || viewportHeight <= 0f) return@snapshotFlow null
            val currentY = scrollYState.floatValue
            val viewport = ReaderViewport(
                bounds = FloatRect.fromLtwh(0f, currentY, viewportWidth, viewportHeight),
            )
            scene.resolveActivePageId(viewport)
        }
            .distinctUntilChanged()
            .collect { activeId ->
                if (activeId != null) {
                    onActivePageChanged(activeId)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewportWidth = size.width.toFloat()
                viewportHeight = size.height.toFloat()
            }
            .pointerInput(scene) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flingJob?.cancel()
                    isFlinging = false
                    onMotionChanged(
                        ViewportMotion(
                            isDragging = true,
                            timestampNanos = down.uptimeMillis * 1_000_000L,
                        ),
                    )

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var lastPointerY = down.position.y

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        val currentPointerY = change.position.y
                        val deltaY = lastPointerY - currentPointerY
                        lastPointerY = currentPointerY

                        val maxScroll = (scene.totalSceneHeight - viewportHeight).coerceAtLeast(0f)
                        val newScroll = (scrollYState.floatValue + deltaY).coerceIn(0f, maxScroll)
                        scrollYState.floatValue = newScroll
                        onScrollProgressChanged(newScroll, maxScroll)
                        onMotionChanged(
                            ViewportMotion(
                                velocityX = 0f,
                                velocityY = deltaY * 60f,
                                isDragging = true,
                                timestampNanos = change.uptimeMillis * 1_000_000L,
                            ),
                        )

                        change.consume()
                    } while (event.changes.any { it.pressed })

                    val velocity = velocityTracker.calculateVelocity()
                    val initialVelocityY = -velocity.y

                    if (kotlin.math.abs(initialVelocityY) > 100f) {
                        isFlinging = true
                        onMotionChanged(
                            ViewportMotion(
                                velocityX = 0f,
                                velocityY = initialVelocityY,
                                isDragging = false,
                                timestampNanos = System.nanoTime(),
                            ),
                        )
                        flingJob = coroutineScope.launch {
                            try {
                                val anim = AnimationState(
                                    initialValue = scrollYState.floatValue,
                                    initialVelocity = initialVelocityY,
                                )
                                anim.animateDecay(decaySpec) {
                                    val maxScroll = (scene.totalSceneHeight - viewportHeight).coerceAtLeast(0f)
                                    val clamped = value.coerceIn(0f, maxScroll)
                                    scrollYState.floatValue = clamped
                                    onScrollProgressChanged(clamped, maxScroll)
                                    onMotionChanged(
                                        ViewportMotion(
                                            velocityX = 0f,
                                            velocityY = this.velocity,
                                            isDragging = false,
                                            timestampNanos = System.nanoTime(),
                                        ),
                                    )
                                    if (clamped == 0f || clamped == maxScroll) {
                                        cancelAnimation()
                                    }
                                }
                            } catch (_: CancellationException) {
                                // Fling was interrupted by a new touch down
                            } finally {
                                isFlinging = false
                                onMotionChanged(ViewportMotion.Idle)
                            }
                        }
                    } else {
                        onMotionChanged(ViewportMotion.Idle)
                    }
                }
            }
            .drawWithContent {
                // DRAW PHASE: Read scrollYState here.
                // Modifying scrollYState schedules a redraw without triggering Composition or Layout!
                val currentY = scrollYState.floatValue
                val vWidth = size.width
                val vHeight = size.height

                if (vWidth > 0f && vHeight > 0f) {
                    val viewport = ReaderViewport(
                        bounds = FloatRect.fromLtwh(0f, currentY, vWidth, vHeight),
                    )
                    val frame = scene.resolve(viewport)
                    drawFrameNodes(frame, currentY, placeholderColor, assetProvider, readerAssetProvider)
                }

                drawContent()
            },
    )
}

/**
 * Pure drawing function rendering visible scene nodes onto the DrawScope canvas.
 */
internal fun DrawScope.drawFrameNodes(
    frame: org.skepsun.kototoro.reader.core.ReaderFrame,
    viewportScrollY: Float,
    placeholderColor: Color,
    assetProvider: (PageId) -> ImageBitmap? = { null },
    readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null,
) {
    for (node in frame.visibleNodes) {
        val screenTop = node.sceneBounds.top - viewportScrollY
        val screenLeft = node.sceneBounds.left
        val nodeWidth = node.sceneBounds.width
        val nodeHeight = node.sceneBounds.height

        val resolvedBitmap: ImageBitmap? = when (val asset = readerAssetProvider?.invoke(node.pageId)) {
            is ReaderImageAsset.ComposeImage -> asset.imageBitmap
            is ReaderImageAsset.AndroidBitmap -> asset.bitmap.asImageBitmap()
            else -> assetProvider(node.pageId)
        }

        if (resolvedBitmap != null) {
            drawImage(
                image = resolvedBitmap,
                dstOffset = IntOffset(screenLeft.roundToInt(), screenTop.roundToInt()),
                dstSize = IntSize(nodeWidth.roundToInt(), nodeHeight.roundToInt()),
            )
        } else {
            drawRect(
                color = placeholderColor,
                topLeft = Offset(screenLeft, screenTop),
                size = Size(nodeWidth, nodeHeight),
            )
        }
    }
}
