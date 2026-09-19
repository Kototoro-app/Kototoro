package org.skepsun.kototoro.reader.render.compose

import android.graphics.Bitmap
import android.os.Build
import android.os.Trace
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
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
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.TileGrid
import org.skepsun.kototoro.reader.image.TileSpec
import org.skepsun.kototoro.reader.image.TileSplit
import org.skepsun.kototoro.reader.image.TileStore
import kotlin.math.ceil
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
    tileStore: TileStore? = null,
    animatedBridge: AnimatedDrawBridge? = remember { AnimatedDrawBridge() },
    onScrollProgressChanged: (scrollY: Float, maxScrollY: Float) -> Unit = { _, _ -> },
    onMotionChanged: (ViewportMotion) -> Unit = {},
    onOverScroll: ((deltaY: Float) -> Unit)? = null,
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
                    val seamPolicy = PageSeamPolicy.forScene(scene.pageSpacingPx, isVertical = true)
                    drawFrameNodes(
                        frame = frame,
                        viewportScrollX = 0f,
                        viewportScrollY = currentY,
                        horizontalOffset = horizontalOffset,
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

/**
 * Pure 2D drawing function rendering visible scene nodes onto the DrawScope canvas.
 */
internal fun DrawScope.drawFrameNodes(
    frame: org.skepsun.kototoro.reader.core.ReaderFrame,
    viewportScrollX: Float = 0f,
    viewportScrollY: Float = 0f,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = 0f,
    seamPolicy: PageSeamPolicy = PageSeamPolicy.VerticalContinuous,
    placeholderColor: Color,
    pageLabelProvider: ((PageId) -> String)? = null,
    textMeasurer: TextMeasurer? = null,
    imageColorFilter: ColorFilter? = null,
    assetProvider: (PageId) -> ImageBitmap? = { null },
    readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null,
    animatedBridge: AnimatedDrawBridge? = null,
    screenPositionProvider: ((VisibleNode) -> Offset)? = null,
) {
    val visiblePageIds = HashSet<PageId>(frame.visibleNodes.size)
    for (node in frame.visibleNodes) {
        visiblePageIds.add(node.pageId)
    }
    if (animatedBridge?.autoUpdateVisiblePages != false) {
        animatedBridge?.updateVisiblePages(visiblePageIds)
    }

    for (node in frame.visibleNodes) {
        val customPos = screenPositionProvider?.invoke(node)
        val screenTop = customPos?.y ?: (node.sceneBounds.top - viewportScrollY + verticalOffset)
        val screenLeft = customPos?.x ?: (node.sceneBounds.left - viewportScrollX + horizontalOffset)
        val nodeWidth = node.sceneBounds.width
        val nodeHeight = node.sceneBounds.height

        val topInt = screenTop.roundToInt()
        val bottomInt = (screenTop + nodeHeight).roundToInt()
        // PageSeamPolicy eliminates GPU bilinear rasterization hairline cracks between adjacent slices
        val heightInt = (bottomInt - topInt + seamPolicy.overlapY).coerceAtLeast(1)

        val leftInt = screenLeft.roundToInt()
        val rightInt = (screenLeft + nodeWidth).roundToInt()
        val widthInt = (rightInt - leftInt + seamPolicy.overlapX).coerceAtLeast(1)

        val asset = readerAssetProvider?.invoke(node.pageId)
        if (asset is ReaderImageAsset.Tiled) {
            drawTiledPage(
                asset = asset,
                node = node,
                screenLeft = screenLeft,
                screenTop = screenTop,
                nodeWidth = nodeWidth,
                nodeHeight = nodeHeight,
                leftInt = leftInt,
                topInt = topInt,
                widthInt = widthInt,
                heightInt = heightInt,
                imageColorFilter = imageColorFilter,
                placeholderColor = placeholderColor,
                pageLabelProvider = pageLabelProvider,
                textMeasurer = textMeasurer,
            )
            continue
        }

        if (asset is ReaderImageAsset.Animated) {
            animatedBridge?.register(node.pageId, asset.drawable)
            drawIntoCanvas { canvas ->
                asset.drawable.setBounds(leftInt, topInt, rightInt, bottomInt)
                asset.drawable.draw(canvas.nativeCanvas)
            }
            continue
        }

        val authoritativeBitmap: ImageBitmap? = when (asset) {
            is ReaderImageAsset.ComposeImage -> asset.imageBitmap
            is ReaderImageAsset.AndroidBitmap -> asset.bitmap.asImageBitmap()
            else -> assetProvider(node.pageId)
        }

        val previewBitmap: ImageBitmap? = if (asset is ReaderImageAsset.Preview) {
            asset.imageBitmap
        } else null

        val resolvedBitmap = authoritativeBitmap ?: previewBitmap

        if (resolvedBitmap != null) {
            // Actual image loaded: draw seamlessly without any borders, dividers, or text overlays
            drawImage(
                image = resolvedBitmap,
                dstOffset = IntOffset(leftInt, topInt),
                dstSize = IntSize(widthInt, heightInt),
                colorFilter = imageColorFilter,
                filterQuality = if (authoritativeBitmap != null) FilterQuality.Medium else FilterQuality.Low,
            )
        } else {
            drawPlaceholder(
                pageId = node.pageId,
                screenLeft = screenLeft,
                screenTop = screenTop,
                nodeWidth = nodeWidth,
                nodeHeight = nodeHeight,
                placeholderColor = placeholderColor,
                pageLabelProvider = pageLabelProvider,
                textMeasurer = textMeasurer,
            )
        }
    }
}

/**
 * Backward-compatible 1D overload for vertical scenes.
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
    animatedBridge: AnimatedDrawBridge? = null,
) = drawFrameNodes(
    frame = frame,
    viewportScrollX = 0f,
    viewportScrollY = viewportScrollY,
    horizontalOffset = horizontalOffset,
    seamPolicy = PageSeamPolicy.VerticalContinuous,
    placeholderColor = placeholderColor,
    pageLabelProvider = pageLabelProvider,
    textMeasurer = textMeasurer,
    imageColorFilter = imageColorFilter,
    assetProvider = assetProvider,
    readerAssetProvider = readerAssetProvider,
    animatedBridge = animatedBridge,
)

/**
 * Logical page areas this layer can already paint for [node] right now.
 *
 * Used to keep the base layer from uploading tiles that a resident target tile paints over. The
 * comparison is by logical rectangle rather than tile key, because the two layers carry different
 * sample sizes in their keys while covering the same page area.
 */
private fun residentLatticeRects(
    layer: ReaderImageAsset.TileLayer,
    node: VisibleNode,
    tileStore: TileStore,
): Set<IntRect> {
    val grid = layer.grid
    if (grid.pageSize.width <= 0 || grid.pageSize.height <= 0) return emptySet()
    if (node.sceneBounds.width <= 0f || node.sceneBounds.height <= 0f) return emptySet()
    val scaleX = grid.pageSize.width.toFloat() / node.sceneBounds.width
    val scaleY = grid.pageSize.height.toFloat() / node.sceneBounds.height
    val visibleLogical = IntRect(
        left = ((node.visibleRegion.left - node.sceneBounds.left) * scaleX).toInt()
            .coerceIn(0, grid.pageSize.width),
        top = ((node.visibleRegion.top - node.sceneBounds.top) * scaleY).toInt()
            .coerceIn(0, grid.pageSize.height),
        right = ceil((node.visibleRegion.right - node.sceneBounds.left) * scaleX).toInt()
            .coerceIn(0, grid.pageSize.width),
        bottom = ceil((node.visibleRegion.bottom - node.sceneBounds.top) * scaleY).toInt()
            .coerceIn(0, grid.pageSize.height),
    )
    return grid.tilesIntersecting(visibleLogical)
        .filter { tileStore.tile(it.key) != null }
        .mapTo(HashSet()) { it.logicalRect }
}

/**
 * Draws a tiled page consisting of a base layer and an optional progressive target LOD layer.
 */
private fun DrawScope.drawTileLayer(
    layer: ReaderImageAsset.TileLayer,
    node: VisibleNode,
    screenLeft: Float,
    screenTop: Float,
    nodeWidth: Float,
    nodeHeight: Float,
    leftInt: Int,
    topInt: Int,
    widthInt: Int,
    heightInt: Int,
    imageColorFilter: ColorFilter?,
    tileStore: TileStore,
    drawOverview: Boolean,
    /**
     * Lattice areas another layer already covers at a higher density. The base layer skips them: a
     * resident target tile paints over the same area, so uploading the base tile underneath it is
     * pure texture traffic (a magnified page measured 42 tiles, about 98MB, per frame).
     */
    skipLogicalRects: Set<IntRect> = emptySet(),
): Boolean {
    val grid = layer.grid
    val orientation = grid.geometry.orientationDegrees
    var hasRenderedAnyContent = false

    if (drawOverview) {
        val overviewTile = layer.overviewKey?.let { tileStore.tile(it) }
        val overviewBmp = (overviewTile?.payload as? Bitmap)?.asImageBitmap()
        if (overviewBmp != null) {
            hasRenderedAnyContent = true
            drawBitmapTransformed(
                image = overviewBmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(overviewBmp.width, overviewBmp.height),
                dstOffset = IntOffset(leftInt, topInt),
                dstSize = IntSize(widthInt, heightInt),
                orientationDegrees = orientation,
                colorFilter = imageColorFilter,
            )
        }
    }

    if (grid.pageSize.width > 0 && grid.pageSize.height > 0 && node.sceneBounds.width > 0f && node.sceneBounds.height > 0f) {
        val scaleX = grid.pageSize.width.toFloat() / node.sceneBounds.width
        val scaleY = grid.pageSize.height.toFloat() / node.sceneBounds.height

        val visRelLeft = (node.visibleRegion.left - node.sceneBounds.left) * scaleX
        val visRelTop = (node.visibleRegion.top - node.sceneBounds.top) * scaleY
        val visRelRight = (node.visibleRegion.right - node.sceneBounds.left) * scaleX
        val visRelBottom = (node.visibleRegion.bottom - node.sceneBounds.top) * scaleY

        val visibleLogical = IntRect(
            left = visRelLeft.toInt().coerceIn(0, grid.pageSize.width),
            top = visRelTop.toInt().coerceIn(0, grid.pageSize.height),
            right = ceil(visRelRight).toInt().coerceIn(0, grid.pageSize.width),
            bottom = ceil(visRelBottom).toInt().coerceIn(0, grid.pageSize.height),
        )

        val intersectingSpecs = grid.tilesIntersecting(visibleLogical)
        val splitOriginX = when (grid.split) {
            TileSplit.NONE -> 0
            TileSplit.LEFT -> 0
            TileSplit.RIGHT -> grid.contentLogicalSize.width - grid.pageSize.width
        }

        val toScreenX = nodeWidth / grid.pageSize.width.toFloat()
        val toScreenY = nodeHeight / grid.pageSize.height.toFloat()

        var drawnTiles = 0
        var drawnTileBytes = 0L
        for (spec in intersectingSpecs) {
            if (spec.logicalRect in skipLogicalRects) continue
            val resident = tileStore.tile(spec.key) ?: continue
            val tileBitmap = (resident.payload as? Bitmap)?.asImageBitmap() ?: continue
            hasRenderedAnyContent = true
            drawnTiles++
            drawnTileBytes += (resident.payload as? Bitmap)?.allocationByteCount?.toLong() ?: 0L

            val params = TiledPageDrawMath.computeTileDrawParams(
                grid = grid,
                spec = spec,
                tileBitmapWidth = tileBitmap.width,
                tileBitmapHeight = tileBitmap.height,
                splitOriginX = splitOriginX,
                screenLeft = screenLeft,
                screenTop = screenTop,
                toScreenX = toScreenX,
                toScreenY = toScreenY,
            ) ?: continue

            drawBitmapTransformed(
                image = tileBitmap,
                srcOffset = params.srcOffset,
                srcSize = params.srcSize,
                dstOffset = params.dstOffset,
                dstSize = params.dstSize,
                orientationDegrees = orientation,
                colorFilter = imageColorFilter,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            // What one layer costs the frame thread: bitmaps drawn here are the ones the GPU has to
            // have as textures, so this separates upload pressure from decode-thread pressure.
            Trace.setCounter("Reader.DrawnTilesPerLayer", drawnTiles.toLong())
            Trace.setCounter("Reader.DrawnTileBytesPerLayer", drawnTileBytes)
        }
    }

    return hasRenderedAnyContent
}

private fun DrawScope.drawTiledPage(
    asset: ReaderImageAsset.Tiled,
    node: VisibleNode,
    screenLeft: Float,
    screenTop: Float,
    nodeWidth: Float,
    nodeHeight: Float,
    leftInt: Int,
    topInt: Int,
    widthInt: Int,
    heightInt: Int,
    imageColorFilter: ColorFilter?,
    placeholderColor: Color,
    pageLabelProvider: ((PageId) -> String)?,
    textMeasurer: TextMeasurer?,
) {
    val renderedBase = drawTileLayer(
        layer = asset.base,
        node = node,
        screenLeft = screenLeft,
        screenTop = screenTop,
        nodeWidth = nodeWidth,
        nodeHeight = nodeHeight,
        leftInt = leftInt,
        topInt = topInt,
        widthInt = widthInt,
        heightInt = heightInt,
        imageColorFilter = imageColorFilter,
        tileStore = asset.tileStore,
        drawOverview = true,
        // Computed before drawing: the base layer is painted first (its overview has to sit under
        // everything), so what the target will cover has to be known up front.
        skipLogicalRects = asset.target
            ?.let { residentLatticeRects(it, node, asset.tileStore) }
            ?: emptySet(),
    )

    val renderedTarget = asset.target?.let { target ->
        drawTileLayer(
            layer = target,
            node = node,
            screenLeft = screenLeft,
            screenTop = screenTop,
            nodeWidth = nodeWidth,
            nodeHeight = nodeHeight,
            leftInt = leftInt,
            topInt = topInt,
            widthInt = widthInt,
            heightInt = heightInt,
            imageColorFilter = imageColorFilter,
            tileStore = asset.tileStore,
            drawOverview = false,
        )
    } ?: false

    // Fallback placeholder if neither overview nor any tiles were ready
    if (!renderedBase && !renderedTarget) {
        drawPlaceholder(
            pageId = node.pageId,
            screenLeft = screenLeft,
            screenTop = screenTop,
            nodeWidth = nodeWidth,
            nodeHeight = nodeHeight,
            placeholderColor = placeholderColor,
            pageLabelProvider = pageLabelProvider,
            textMeasurer = textMeasurer,
        )
    }
}

private fun DrawScope.drawBitmapTransformed(
    image: ImageBitmap,
    srcOffset: IntOffset,
    srcSize: IntSize,
    dstOffset: IntOffset,
    dstSize: IntSize,
    orientationDegrees: Int,
    colorFilter: ColorFilter?,
) {
    if (orientationDegrees == 0) {
        drawImage(
            image = image,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = dstOffset,
            dstSize = dstSize,
            colorFilter = colorFilter,
            filterQuality = FilterQuality.Medium,
        )
    } else {
        val centerX = dstOffset.x + dstSize.width / 2f
        val centerY = dstOffset.y + dstSize.height / 2f
        withTransform({
            rotate(orientationDegrees.toFloat(), pivot = Offset(centerX, centerY))
        }) {
            drawImage(
                image = image,
                srcOffset = srcOffset,
                srcSize = srcSize,
                dstOffset = dstOffset,
                dstSize = dstSize,
                colorFilter = colorFilter,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private fun DrawScope.drawPlaceholder(
    pageId: PageId,
    screenLeft: Float,
    screenTop: Float,
    nodeWidth: Float,
    nodeHeight: Float,
    placeholderColor: Color,
    pageLabelProvider: ((PageId) -> String)?,
    textMeasurer: TextMeasurer?,
) {
    // 1. Base placeholder background fill
    drawRect(
        color = placeholderColor,
        topLeft = Offset(screenLeft, screenTop),
        size = Size(nodeWidth, nodeHeight),
    )

    // Subtle alternating tint for adjacent placeholders
    val isEven = (pageId.value % 2L == 0L)
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
        val label = pageLabelProvider(pageId)
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

internal object TiledPageDrawMath {
    data class TileDrawParams(
        val srcOffset: IntOffset,
        val srcSize: IntSize,
        val dstOffset: IntOffset,
        val dstSize: IntSize,
    )

    fun computeTileDrawParams(
        grid: TileGrid,
        spec: org.skepsun.kototoro.reader.image.TileSpec,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        splitOriginX: Int,
        screenLeft: Float,
        screenTop: Float,
        toScreenX: Float,
        toScreenY: Float,
    ): TileDrawParams? {
        val contentLogical = spec.logicalRect.translate(splitOriginX, 0)
        val encodedContent = grid.geometry.mapLogicalToEncodedRegion(contentLogical)

        val rawSrcLeft = (encodedContent.left - spec.decodeRegion.left).coerceAtLeast(0)
        val rawSrcTop = (encodedContent.top - spec.decodeRegion.top).coerceAtLeast(0)
        val rawSrcWidth = encodedContent.width
        val rawSrcHeight = encodedContent.height

        val sampleSize = spec.sampleSize.coerceAtLeast(1)
        val srcX = rawSrcLeft / sampleSize
        val srcY = rawSrcTop / sampleSize
        val srcW = (rawSrcWidth / sampleSize).coerceAtMost(tileBitmapWidth - srcX)
        val srcH = (rawSrcHeight / sampleSize).coerceAtMost(tileBitmapHeight - srcY)

        if (srcW <= 0 || srcH <= 0) return null

        val tileScreenLeft = screenLeft + spec.logicalRect.left * toScreenX
        val tileScreenTop = screenTop + spec.logicalRect.top * toScreenY
        val tileScreenWidth = spec.logicalRect.width * toScreenX
        val tileScreenHeight = spec.logicalRect.height * toScreenY

        val dstLeft = tileScreenLeft.roundToInt()
        val dstTop = tileScreenTop.roundToInt()
        val dstRight = (tileScreenLeft + tileScreenWidth).roundToInt()
        val dstBottom = (tileScreenTop + tileScreenHeight).roundToInt()
        val dstW = (dstRight - dstLeft).coerceAtLeast(1)
        val dstH = (dstBottom - dstTop).coerceAtLeast(1)

        return TileDrawParams(
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset(dstLeft, dstTop),
            dstSize = IntSize(dstW, dstH),
        )
    }
}

