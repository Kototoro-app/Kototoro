package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchPriority
import org.skepsun.kototoro.reader.core.ReaderPrediction
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.render.arr.AdaptiveRefreshRateHelper
import org.skepsun.kototoro.reader.render.compose.ComposeSceneRenderer
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import kotlin.math.roundToInt

/**
 * End-to-end Webtoon reader composable driven by the decoupled Reader Scene Engine (ADR 0002).
 *
 * Integrates:
 * - [VerticalReaderScene] for pure geometric layout, anchored correction, and active page detection.
 * - [ReaderPrediction] for lookahead prefetching based on [ViewportMotion].
 * - [KototoroImagePipelineAdapter] for resource fetching.
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

    val adapter = remember(imagePipeline, imageLoader, pages) {
        KototoroImagePipelineAdapter(
            context = context,
            composePipeline = imagePipeline,
            imageLoader = imageLoader,
            scope = coroutineScope,
            pageLookup = pageLookup,
        )
    }

    val predictor = remember { ReaderPrediction() }

    // Scene is created once width is measured
    val scene = remember(pages, viewportWidthPx) {
        if (viewportWidthPx <= 0f) null
        else {
            VerticalReaderScene(
                availableWidth = viewportWidthPx.toInt(),
                defaultViewportHeight = viewportHeightPx.toInt().coerceAtLeast(1000),
                initialPages = createInitialScenePageHints(pages),
            )
        }
    }

    // In-memory decoded image cache for visible and near-visible pages
    val decodedBitmaps = remember(pages) { mutableStateMapOf<PageId, ImageBitmap>() }
    var currentMotion by remember { mutableStateOf(ViewportMotion.Idle) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(readerBackgroundColor))
            .onSizeChanged { size ->
                viewportWidthPx = size.width.toFloat()
                viewportHeightPx = size.height.toFloat()
            },
    ) {
        val activeScene = scene ?: return@Box

        ComposeSceneRenderer(
            scene = activeScene,
            initialScrollY = initialScroll.toFloat(),
            placeholderColor = Color.DarkGray,
            readerAssetProvider = { id ->
                decodedBitmaps[id]?.let { ReaderImageAsset.ComposeImage(id, it) }
            },
            onActivePageChanged = { activePageId ->
                val page = pageLookup(activePageId)
                if (page != null) {
                    onPagesChanged(page.readerKey, page.readerKey, page.readerKey)
                }
            },
            onScrollProgressChanged = { currentY, _ ->
                val vp = ReaderViewport(FloatRect.fromLtwh(0f, currentY, viewportWidthPx, viewportHeightPx))
                val activeId = activeScene.resolveActivePageId(vp)
                val activePage = activeId?.let(pageLookup) ?: pages.firstOrNull()
                if (activePage != null) {
                    val pageRelativeScroll = resolveActivePageRelativeScroll(activeScene, vp, activeId)
                    onInternalScrollChanged(activePage, pageRelativeScroll)
                }

                // Trigger prediction and asynchronous decoding
                val requests = predictor.predict(activeScene, vp, currentMotion)
                adapter.schedulePrefetch(requests)

                // Decode urgent requests
                for (req in requests) {
                    if (req.priority == PrefetchPriority.IMMEDIATE || req.priority == PrefetchPriority.HIGH) {
                        val pageId = req.pageId
                        if (!decodedBitmaps.containsKey(pageId)) {
                            val page = pageLookup(pageId) ?: continue
                            coroutineScope.launch(Dispatchers.IO) {
                                val cachedState = imagePipeline.cachedState(page.readerKey)
                                val uri = when (cachedState) {
                                    is ComposeReaderImageState.OriginalReady -> cachedState.original
                                    is ComposeReaderImageState.EnhancedReady -> cachedState.enhanced
                                    else -> null
                                }
                                if (uri != null) {
                                    val request = ImageRequest.Builder(context)
                                        .data(uri)
                                        .build()
                                    val result = imageLoader.execute(request)
                                    if (result is SuccessResult) {
                                        val bmp = result.image.toBitmap()
                                        val imageBmp = bmp.asImageBitmap()
                                        withContext(Dispatchers.Main) {
                                            activeScene.updatePageHint(
                                                pageId = pageId,
                                                newHint = PageGeometryHint.Exact(bmp.width, bmp.height),
                                                currentViewport = vp,
                                            )
                                            decodedBitmaps[pageId] = imageBmp
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            onMotionChanged = { motion ->
                currentMotion = motion
                AdaptiveRefreshRateHelper.applyPreference(view, motion)
            },
        )
    }
}

internal fun createInitialScenePageHints(
    pages: List<ReaderPage>,
    defaultRatio: Float = 1.0f,
): List<Pair<PageId, PageGeometryHint>> {
    return pages.map { page ->
        PageId(page.readerKey) to PageGeometryHint.Estimated(ratio = defaultRatio)
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

