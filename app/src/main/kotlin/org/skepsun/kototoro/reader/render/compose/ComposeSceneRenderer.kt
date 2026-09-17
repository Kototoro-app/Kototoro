package org.skepsun.kototoro.reader.render.compose

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 * 3. Reading semantics remain in the scene frame and are not recomputed by the renderer.
 */
@Composable
fun ComposeSceneRenderer(
    scene: VerticalReaderScene,
    modifier: Modifier = Modifier,
    initialScrollY: Float = 0f,
    scrollState: ComposeSceneScrollState = rememberComposeSceneScrollState(initialScrollY),
    placeholderColor: Color = Color.DarkGray,
    pageLabelProvider: ((PageId) -> String)? = null,
    imageColorFilter: ColorFilter? = null,
    assetProvider: (PageId) -> ImageBitmap? = { null },
    readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null,
    onScrollProgressChanged: (scrollY: Float, maxScrollY: Float) -> Unit = { _, _ -> },
    onMotionChanged: (ViewportMotion) -> Unit = {},
    onOverScroll: ((deltaY: Float) -> Unit)? = null,
    onReleaseOverScroll: (() -> Unit)? = null,
) {
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val decaySpec = remember(density) { splineBasedDecay<Float>(density) }
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewportWidth = size.width.toFloat()
                viewportHeight = size.height.toFloat()
                scrollState.maxScrollY = (scene.totalSceneHeight - viewportHeight).coerceAtLeast(0f)
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

                        val currentPointerY = change.position.y
                        val currentPointerX = change.position.x
                        val deltaY = lastPointerY - currentPointerY
                        lastPointerY = currentPointerY

                        val currentUptimeMillis = change.uptimeMillis
                        val dtMillis = (currentUptimeMillis - lastUptimeMillis).coerceAtLeast(1L)
                        lastUptimeMillis = currentUptimeMillis

                        if (!dragStarted) {
                            val totalDisplacementY = kotlin.math.abs(down.position.y - currentPointerY)
                            val totalDisplacementX = kotlin.math.abs(down.position.x - currentPointerX)
                            if (totalDisplacementY > touchSlop || totalDisplacementX > touchSlop) {
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
                            val instantVelocityY = (deltaY * 1000f) / dtMillis

                            val maxScroll = (scene.totalSceneHeight - viewportHeight).coerceAtLeast(0f)
                            scrollState.maxScrollY = maxScroll

                            val desiredScroll = scrollState.scrollY + deltaY
                            val newScroll = desiredScroll.coerceIn(0f, maxScroll)
                            val overscrollDelta = desiredScroll - newScroll
                            if (overscrollDelta != 0f && onOverScroll != null) {
                                onOverScroll(overscrollDelta)
                            }

                            val motion = ViewportMotion(
                                velocityX = 0f,
                                velocityY = instantVelocityY,
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
                        val initialVelocityY = -velocity.y

                        if (kotlin.math.abs(initialVelocityY) > 100f) {
                            scrollState.isFlinging = true
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
                                    val animState = AnimationState(
                                        initialValue = scrollState.scrollY,
                                        initialVelocity = initialVelocityY,
                                    )
                                    var lastAnimatedY = scrollState.scrollY
                                    animState.animateDecay(decaySpec) {
                                        val frameDelta = value - lastAnimatedY
                                        lastAnimatedY = value
                                        val maxScroll = (scene.totalSceneHeight - viewportHeight).coerceAtLeast(0f)
                                        scrollState.maxScrollY = maxScroll
                                        val clamped = (scrollState.scrollY + frameDelta).coerceIn(0f, maxScroll)
                                        val motion = ViewportMotion(
                                            velocityX = 0f,
                                            velocityY = this.velocity,
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
                // DRAW PHASE: Read scrollState.scrollY here.
                // Modifying scrollState schedules a redraw without triggering Composition or Layout!
                val currentY = scrollState.scrollY
                val vWidth = size.width
                val vHeight = size.height

                if (vWidth > 0f && vHeight > 0f) {
                    val horizontalOffset = ((vWidth - scene.availableWidth) / 2f).coerceAtLeast(0f)
                    val viewport = ReaderViewport(
                        bounds = FloatRect.fromLtwh(0f, currentY, scene.availableWidth.toFloat(), vHeight),
                    )
                    val frame = scene.resolve(viewport)
                    drawFrameNodes(
                        frame = frame,
                        viewportScrollY = currentY,
                        horizontalOffset = horizontalOffset,
                        placeholderColor = placeholderColor,
                        pageLabelProvider = pageLabelProvider,
                        textMeasurer = textMeasurer,
                        imageColorFilter = imageColorFilter,
                        assetProvider = assetProvider,
                        readerAssetProvider = readerAssetProvider,
                    )
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
    horizontalOffset: Float = 0f,
    placeholderColor: Color,
    pageLabelProvider: ((PageId) -> String)? = null,
    textMeasurer: TextMeasurer? = null,
    imageColorFilter: ColorFilter? = null,
    assetProvider: (PageId) -> ImageBitmap? = { null },
    readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null,
) {
    for (node in frame.visibleNodes) {
        val screenTop = node.sceneBounds.top - viewportScrollY
        val screenLeft = node.sceneBounds.left + horizontalOffset
        val nodeWidth = node.sceneBounds.width
        val nodeHeight = node.sceneBounds.height

        val topInt = screenTop.roundToInt()
        val bottomInt = (screenTop + nodeHeight).roundToInt()
        // 1px vertical overlap (+1) completely eliminates GPU bilinear rasterization hairline cracks between adjacent slices
        val heightInt = (bottomInt - topInt + 1).coerceAtLeast(1)

        val leftInt = screenLeft.roundToInt()
        val rightInt = (screenLeft + nodeWidth).roundToInt()
        val widthInt = (rightInt - leftInt).coerceAtLeast(1)

        val resolvedBitmap: ImageBitmap? = when (val asset = readerAssetProvider?.invoke(node.pageId)) {
            is ReaderImageAsset.ComposeImage -> asset.imageBitmap
            is ReaderImageAsset.AndroidBitmap -> asset.bitmap.asImageBitmap()
            else -> assetProvider(node.pageId)
        }

        if (resolvedBitmap != null) {
            // Actual image loaded: draw seamlessly without any borders, dividers, or text overlays
            drawImage(
                image = resolvedBitmap,
                dstOffset = IntOffset(leftInt, topInt),
                dstSize = IntSize(widthInt, heightInt),
                colorFilter = imageColorFilter,
                filterQuality = FilterQuality.Medium,
            )
        } else {
            // 1. Base placeholder background fill
            drawRect(
                color = placeholderColor,
                topLeft = Offset(screenLeft, screenTop),
                size = Size(nodeWidth, nodeHeight),
            )

            // Subtle alternating tint for adjacent placeholders
            val isEven = (node.pageId.value % 2L == 0L)
            if (!isEven) {
                drawRect(
                    color = Color(0x0FFFFFFF),
                    topLeft = Offset(screenLeft, screenTop),
                    size = Size(nodeWidth, nodeHeight),
                )
            }

            // 2. Light outline border
            drawRect(
                color = Color(0x33FFFFFF),
                topLeft = Offset(screenLeft, screenTop),
                size = Size(nodeWidth, nodeHeight),
                style = Stroke(width = 2f),
            )

            // 3. Top and bottom boundary divider lines
            drawLine(
                color = Color(0xFF4A4A4D),
                start = Offset(screenLeft, screenTop),
                end = Offset(screenLeft + nodeWidth, screenTop),
                strokeWidth = 3f,
            )
            drawLine(
                color = Color(0xFF4A4A4D),
                start = Offset(screenLeft, screenTop + nodeHeight),
                end = Offset(screenLeft + nodeWidth, screenTop + nodeHeight),
                strokeWidth = 3f,
            )

            // 4. Centered floating page label badge in visible viewport slice
            if (pageLabelProvider != null && textMeasurer != null) {
                val label = pageLabelProvider(node.pageId)
                if (label.isNotBlank()) {
                    val textLayout = textMeasurer.measure(
                        text = label,
                        style = TextStyle(
                            color = Color(0x99FFFFFF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    val visibleNodeTop = maxOf(screenTop, 0f)
                    val visibleNodeBottom = minOf(screenTop + nodeHeight, size.height)
                    if (visibleNodeBottom > visibleNodeTop) {
                        val badgePaddingH = 24f
                        val badgePaddingV = 12f
                        val badgeWidth = textLayout.size.width + badgePaddingH * 2
                        val badgeHeight = textLayout.size.height + badgePaddingV * 2
                        val centerY = (visibleNodeTop + visibleNodeBottom) / 2f
                        val centerX = screenLeft + (nodeWidth - badgeWidth) / 2f

                        drawRoundRect(
                            color = Color(0xCC141416),
                            topLeft = Offset(centerX, centerY - badgeHeight / 2f),
                            size = Size(badgeWidth, badgeHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                        )
                        drawText(
                            textLayoutResult = textLayout,
                            topLeft = Offset(centerX + badgePaddingH, centerY - textLayout.size.height / 2f),
                        )
                    }
                }
            }
        }
    }
}
