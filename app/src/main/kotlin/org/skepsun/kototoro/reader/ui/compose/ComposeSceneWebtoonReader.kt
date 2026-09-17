package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderPrediction
import org.skepsun.kototoro.reader.core.ReaderPredictionConfig
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.ReaderImageLoadState
import org.skepsun.kototoro.reader.image.ReaderImagePipeline
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.reader.render.arr.AdaptiveRefreshRateHelper
import org.skepsun.kototoro.reader.render.compose.ComposeSceneRenderer
import org.skepsun.kototoro.reader.render.compose.rememberComposeSceneScrollState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import kotlin.math.roundToInt

/**
 * End-to-end Webtoon reader composable driven by the decoupled Reader Scene Engine (ADR 0002).
 *
 * Integrates:
 * - [VerticalReaderScene] for pure geometric layout, anchored correction, and active page detection.
 * - [ReaderPrediction] for lookahead prefetching based on [ViewportMotion].
 * - [KototoroImagePipelineAdapter] for resource fetching and deduplicated image decoding.
 * - [ComposeSceneRenderer] for Draw-phase rendering bypassing Composition/Layout.
 */
@Composable
fun ComposeSceneWebtoonReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    initialScroll: Int,
    imageLoader: ImageLoader,
    imagePipeline: ComposeReaderImagePipeline,
    onPagesChanged: (lowerKey: Long, upperKey: Long, activeKey: Long) -> Unit,
    onInternalScrollChanged: (page: ReaderPage, scroll: Int) -> Unit,
    isPreloadReductionEnabled: Boolean = false,
    isCropEnabled: Boolean = false,
    bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    imageColorFilter: ColorFilter? = null,
    modifier: Modifier = Modifier,
    readerBackgroundColor: Int = android.graphics.Color.BLACK,
) {
    if (pages.isEmpty()) return

    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    var viewportWidthPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    val pageLookup: (PageId) -> ReaderPage? = remember(pages) {
        val map = pages.associateBy { it.readerKey }
        val lookup: (PageId) -> ReaderPage? = { id -> map[id.value] }
        lookup
    }

    val adapter = remember(imagePipeline, imageLoader, pages, isCropEnabled, bitmapConfig) {
        KototoroImagePipelineAdapter(
            context = context,
            composePipeline = imagePipeline,
            imageLoader = imageLoader,
            scope = coroutineScope,
            pageLookup = pageLookup,
            isCropEnabled = isCropEnabled,
            bitmapConfig = bitmapConfig,
        )
    }

    val predictor = remember(isPreloadReductionEnabled) {
        val config = if (isPreloadReductionEnabled) {
            ReaderPredictionConfig(
                staticAheadFraction = 1.0f,
                staticBehindFraction = 0.5f,
                lookaheadHorizonSeconds = 0.4f,
                maxLookaheadExtraPx = 3000f,
            )
        } else {
            ReaderPredictionConfig(
                staticAheadFraction = 3.0f,
                staticBehindFraction = 1.5f,
                lookaheadHorizonSeconds = 0.8f,
                maxLookaheadExtraPx = 8000f,
            )
        }
        ReaderPrediction(config)
    }

    // Scene is created once width is measured
    val scene = remember(pages, viewportWidthPx, adapter) {
        if (viewportWidthPx <= 0f) null
        else {
            VerticalReaderScene(
                availableWidth = viewportWidthPx.toInt(),
                defaultViewportHeight = viewportHeightPx.toInt().coerceAtLeast(1000),
                initialPages = createInitialScenePageHints(pages, adapter),
            )
        }
    }

    val initialPosition = remember(pages, initialPage) {
        initialPage.coerceIn(pages.indices)
    }
    val initialTargetScrollY = remember(scene, initialPosition, initialScroll) {
        if (scene != null && initialPosition in pages.indices) {
            val targetPage = pages[initialPosition]
            val pageTop = scene.resolvePageScrollPosition(PageId(targetPage.readerKey)) ?: 0f
            pageTop + initialScroll.toFloat()
        } else {
            0f
        }
    }
    val initialMaxScroll = remember(scene, viewportHeightPx) {
        if (scene != null && viewportHeightPx > 0f) {
            (scene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
        } else {
            Float.MAX_VALUE
        }
    }
    val scrollState = rememberComposeSceneScrollState(
        initialScrollY = initialTargetScrollY,
        maxScrollY = initialMaxScroll,
        key = scene,
    )
    var hasAppliedInitialPosition by remember(pages) { mutableStateOf(false) }

    // In-memory decoded image cache for visible and near-visible pages
    val decodedBitmaps = remember(pages, adapter) {
        val map = mutableStateMapOf<PageId, ImageBitmap>()
        for (page in pages) {
            val pageId = PageId(page.readerKey)
            val cached = adapter.getCachedAsset(pageId)
            if (cached is ReaderImageAsset.ComposeImage) {
                map[pageId] = cached.imageBitmap
            }
        }
        map
    }
    var currentMotion by remember { mutableStateOf(ViewportMotion.Idle) }
    var lastReportedPages by remember(pages) { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var statusPageId by remember(pages) { mutableStateOf(PageId(pages[initialPosition].readerKey)) }

    val activeScene = scene

    // Unified resource window update used by both initial prime, scroll events, and asset resolution
    fun updateResourceWindow(
        currentScene: VerticalReaderScene,
        currentY: Float,
        motion: ViewportMotion,
    ) {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
        val vp = ReaderViewport(FloatRect.fromLtwh(0f, currentY, viewportWidthPx, viewportHeightPx))
        val frame = currentScene.resolve(vp)

        // 1. Semantic update (lowerKey, upperKey, activeKey) - only report once initial positioning is ready
        val visibleNodes = frame.visibleNodes
        currentScene.resolveActivePageId(vp)?.let { statusPageId = it }
        if (visibleNodes.isNotEmpty() && hasAppliedInitialPosition) {
            val lowerKey = visibleNodes.first().pageId.value
            val upperKey = visibleNodes.last().pageId.value
            val activeId = currentScene.resolveActivePageId(vp) ?: visibleNodes.first().pageId
            val activeKey = activeId.value

            val newReported = Triple(lowerKey, upperKey, activeKey)
            if (lastReportedPages != newReported) {
                lastReportedPages = newReported
                onPagesChanged(lowerKey, upperKey, activeKey)
            }

            val activePage = pageLookup(activeId) ?: pages.firstOrNull()
            if (activePage != null) {
                val pageRelativeScroll = resolveActivePageRelativeScroll(currentScene, vp, activeId)
                onInternalScrollChanged(activePage, pageRelativeScroll)
            }
        }

        // 2. Predict lookahead and trigger prefetch
        val requests = predictor.predict(currentScene, vp, motion)
        adapter.schedulePrefetch(requests)

        // 3. Ensure foreground acquire for all visible nodes
        for (node in visibleNodes) {
            val pageId = node.pageId
            if (!decodedBitmaps.containsKey(pageId)) {
                coroutineScope.launch {
                    adapter.acquireAsset(pageId)
                }
            }
        }

        // 4. Memory Cache Sliding Window Eviction:
        // Keep active visible nodes, plus lookahead (~7 ahead) and retention (~4 behind).
        // Evict out-of-range bitmaps from memory while preserving their exact geometry in VerticalReaderScene.
        if (visibleNodes.isNotEmpty()) {
            val visibleIndices = visibleNodes.mapNotNull { pageLookup(it.pageId)?.index }
            if (visibleIndices.isNotEmpty()) {
                val minIndex = visibleIndices.min()
                val maxIndex = visibleIndices.max()
                val minKeep = (minIndex - 4).coerceAtLeast(0)
                val maxKeep = (maxIndex + 7).coerceAtMost(pages.size - 1)

                val toEvict = mutableListOf<PageId>()
                for (cachedId in decodedBitmaps.keys) {
                    val idx = pageLookup(cachedId)?.index ?: continue
                    if (idx < minKeep || idx > maxKeep) {
                        toEvict.add(cachedId)
                    }
                }
                for (evictId in toEvict) {
                    decodedBitmaps.remove(evictId)
                    adapter.evictAsset(evictId)
                }
            }
        }
    }

    // Wire anchor compensation whenever an exact asset dimension is resolved
    DisposableEffect(adapter, activeScene) {
        if (activeScene != null) {
            adapter.onAssetLoaded = { pageId, asset ->
                if (asset is ReaderImageAsset.ComposeImage) {
                    val bmp = asset.imageBitmap
                    decodedBitmaps[pageId] = bmp
                    val vp = ReaderViewport(
                        FloatRect.fromLtwh(0f, scrollState.scrollY, viewportWidthPx, viewportHeightPx),
                    )
                    val compensation = activeScene.updatePageHint(
                        pageId = pageId,
                        newHint = PageGeometryHint.Exact(bmp.width, bmp.height),
                        currentViewport = vp,
                    )
                    if (viewportHeightPx > 0f) {
                        scrollState.maxScrollY = (activeScene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
                    }
                    if (compensation != null && compensation.deltaY != 0f) {
                        scrollState.snapBy(compensation.deltaY)
                    }
                    // Immediately refresh resource window so newly visible pages from contraction are acquired
                    updateResourceWindow(activeScene, scrollState.scrollY, currentMotion)
                }
            }
        }
        onDispose {
            adapter.onAssetLoaded = null
        }
    }

    // Initial positioning and viewport priming
    LaunchedEffect(activeScene, viewportWidthPx, viewportHeightPx) {
        if (activeScene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
            if (!hasAppliedInitialPosition) {
                val maxScroll = (activeScene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
                scrollState.maxScrollY = maxScroll
                val targetY = initialTargetScrollY.coerceIn(0f, maxScroll)
                scrollState.snapTo(targetY)
                hasAppliedInitialPosition = true
            }
            updateResourceWindow(activeScene, scrollState.scrollY, ViewportMotion.Idle)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(readerBackgroundColor))
            .onSizeChanged { size ->
                viewportWidthPx = size.width.toFloat()
                viewportHeightPx = size.height.toFloat()
            },
    ) {
        if (activeScene != null) {
            ComposeSceneRenderer(
                scene = activeScene,
                scrollState = scrollState,
                placeholderColor = Color.DarkGray,
                pageLabelProvider = { pageId ->
                    pageLookup(pageId)?.index?.let { "第 ${it + 1} 页" } ?: ""
                },
                imageColorFilter = imageColorFilter,
                modifier = Modifier.graphicsLayer {
                    alpha = if (hasAppliedInitialPosition) 1f else 0f
                },
                readerAssetProvider = { id ->
                    decodedBitmaps[id]?.let { ReaderImageAsset.ComposeImage(id, it) } ?: adapter.getCachedAsset(id)
                },
                onScrollProgressChanged = { currentY, _ ->
                    updateResourceWindow(activeScene, currentY, currentMotion)
                },
                onMotionChanged = { motion ->
                    currentMotion = motion
                    AdaptiveRefreshRateHelper.applyPreference(view, motion)
                },
            )
            SceneReaderLoadStatus(
                pipeline = adapter,
                pageId = statusPageId,
                onRetry = { coroutineScope.launch { adapter.retryAsset(statusPageId) } },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Only the active page's low-frequency resource state participates in this overlay's composition. */
@Composable
private fun SceneReaderLoadStatus(
    pipeline: ReaderImagePipeline,
    pageId: PageId,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val states by pipeline.loadStates.collectAsStateWithLifecycle()
    when (val state = states[pageId]) {
        ReaderImageLoadState.Ready -> Unit
        is ReaderImageLoadState.Failed -> Surface(
            modifier = modifier.padding(24.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.cause.getDisplayMessage(LocalContext.current.resources),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        else -> Box(modifier) {
            ReaderPageLoading((state as? ReaderImageLoadState.Loading)?.progress)
        }
    }
}

internal fun createInitialScenePageHints(
    pages: List<ReaderPage>,
    adapter: KototoroImagePipelineAdapter? = null,
    defaultRatio: Float = 1.0f,
): List<Pair<PageId, PageGeometryHint>> {
    return pages.map { page ->
        val pageId = PageId(page.readerKey)
        val cached = adapter?.getCachedAsset(pageId)
        val hint = if (cached is ReaderImageAsset.ComposeImage) {
            PageGeometryHint.Exact(cached.imageBitmap.width, cached.imageBitmap.height)
        } else {
            PageGeometryHint.Estimated(ratio = defaultRatio)
        }
        pageId to hint
    }
}

internal fun resolveActivePageRelativeScroll(
    scene: VerticalReaderScene,
    viewport: ReaderViewport,
    activePageId: PageId?,
): Int {
    if (activePageId == null) return 0
    val frame = scene.resolve(viewport)
    val node = frame.visibleNodes.find { it.pageId == activePageId }
    return if (node != null) {
        (viewport.bounds.top - node.sceneBounds.top).roundToInt().coerceAtLeast(0)
    } else {
        0
    }
}
