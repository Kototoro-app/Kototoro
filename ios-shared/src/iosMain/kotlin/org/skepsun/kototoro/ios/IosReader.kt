package org.skepsun.kototoro.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.skepsun.kototoro.reader.core.*
import org.skepsun.kototoro.reader.image.DecodePlan
import org.skepsun.kototoro.reader.image.DecodePlanner
import org.skepsun.kototoro.reader.image.ImageSourceMetadata
import org.skepsun.kototoro.reader.image.RendererCapabilities
import platform.Foundation.NSData
import platform.Foundation.create

data class IosReaderPlacement(
    val index: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val segment: Int = 0,
)
data class IosReaderSlot(val index: Int, val anchor: Int, val placements: List<IosReaderPlacement>)
/** Stable chapter-local identity, independent of its current index in the scene window. */
data class IosReaderPage(val chapterId: String, val index: Int)
data class IosReaderProgress(val index: Int, val offset: Float)
/** A continuous viewport's reading page and offset (as [IosReader.progress]) with its last visible page. */
data class IosReaderPosition(val index: Int, val offset: Float, val lastVisible: Int)
data class IosReaderViewport(val x: Float, val y: Float)
data class IosReaderSize(val width: Int, val height: Int)
data class IosReaderRect(val left: Int, val top: Int, val width: Int, val height: Int)
/**
 * One page of a continuous strip's resource window ([IosReader.resourceWindow]): [priority] 0 is on screen, 1 is the
 * near lookahead (decoded before it scrolls in), 2 the far lookahead (downloaded only, [decode] false).
 */
data class IosReaderRequest(val index: Int, val priority: Int, val decode: Boolean)
data class IosReaderTile(
    val row: Int,
    val col: Int,
    val sampleSize: Int,
    val logicalLeft: Int,
    val logicalTop: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val decodeLeft: Int,
    val decodeTop: Int,
    val decodeWidth: Int,
    val decodeHeight: Int,
)

/**
 * How one paged slot looks during a turn ([PageTurnTransition]): [shift] moves it along the paging axis by a fraction
 * of the page in reading order (cancelling its scroll travel when it stays put), and a folding page draws its front on
 * the [keepX]/[keepY] side of the fold line through ([topX], [topY]) and ([bottomX], [bottomY]) with its back mirrored
 * across that line. Points are page-local and physical.
 */
data class IosPageTurn(
    val shift: Float,
    val alpha: Float,
    val shade: Float,
    val folding: Boolean,
    val topX: Float = 0f,
    val topY: Float = 0f,
    val bottomX: Float = 0f,
    val bottomY: Float = 0f,
    val keepX: Float = 0f,
    val keepY: Float = 0f,
)

/** Geometry and reading semantics stay in reader-core; SwiftUI owns pixels, gestures and native scrolling. */
class IosReader {
    private var scene: MutableReaderScene? = null
    private var width = 1
    private var height = 1
    private val sizes = mutableMapOf<Int, PageGeometryHint>()
    private val specLookup = mutableMapOf<Long, Pair<Int, Int>>()
    private var count = 0
    private var pageKeys: List<IosReaderPage> = emptyList()
    private var mode = 0
    private var doublePage = false
    private var cover = false
    private var fit = 0
    private var splitWide = false
    private var horizontalReversed = false
    /** Continuous modes' cross-axis zoom: the strip is laid out this many times wider (taller for mode 4). */
    private var zoom = 1f
    /**
     * Continuous scenes identify pages by chapter-local identity, as Android's webtoon keys pages by `readerKey`:
     * a window that gains or loses a chapter keeps every page's id, so [MutableReaderScene.updatePages] keeps the
     * reading anchor. Paged scenes keep window positions (with split halves in [specLookup]).
     */
    private val stableIds = HashMap<IosReaderPage, Long>()
    private var nextStableId = STABLE_ID_BASE
    private val indexById = HashMap<Long, Int>()
    /**
     * Bumped whenever [slots] may change while the scene's own [ReaderScene.revision] does not: a new scene, new page
     * ids or a reset. With [sceneRevision] it tells the host its slots are still current.
     */
    var layoutGeneration = 0L
        private set
    val sceneRevision: Long get() = scene?.revision ?: -1L
    /** The last resolved viewport: one scroll frame asks for its position, last page and resource window. */
    private var resolvedScene: ReaderScene? = null
    private var resolvedRevision = -1L
    private var resolvedGeneration = -1L
    private var resolvedViewport: ReaderViewport? = null
    private var resolvedFrame: ReaderFrame? = null
    /** The continuous resource window: Android's webtoon one, or its reduced form (see [setPreloadReduction]). */
    private var prediction = ReaderPrediction(FULL_PREDICTION)
    private var preloadReduction = false
    /**
     * The window's still-unknown pages are laid out at the decoded pages' average ratio once it converges, as
     * Android's webtoon does (`ReaderChapterRatioEstimator`): a page placeholder then has about its real size, where
     * an estimated page is one screen tall. Continuous modes only; a new scene starts over.
     */
    private var ratioEstimator = ReaderChapterRatioEstimator()
    private var unknownRatio: Float? = null
    /** Android's decode planning (single bitmap or tiles), shared through reader-core. */
    private val decodePlanner = DecodePlanner()

    fun configure(count: Int, width: Int, height: Int, mode: Int, doublePage: Boolean, cover: Boolean, fit: Int) =
        configure(count, width, height, mode, doublePage, cover, fit, false)

    fun configure(
        count: Int,
        width: Int,
        height: Int,
        mode: Int,
        doublePage: Boolean,
        cover: Boolean,
        fit: Int,
        splitWide: Boolean,
    ) = configure(count, width, height, mode, doublePage, cover, fit, splitWide, false)

    fun configure(
        count: Int,
        width: Int,
        height: Int,
        mode: Int,
        doublePage: Boolean,
        cover: Boolean,
        fit: Int,
        splitWide: Boolean,
        horizontalReversed: Boolean,
    ) {
        require(count >= 0)
        this.count = count; this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        this.mode = mode; this.doublePage = doublePage; this.cover = cover; this.fit = fit; this.splitWide = splitWide
        this.horizontalReversed = horizontalReversed
        sizes.keys.retainAll(0 until count)
        if (pageKeys.size != count) pageKeys = emptyList()
        rebuild()
    }

    fun chapterWindow(
        previous: List<IosReaderPage>, current: List<IosReaderPage>, next: List<IosReaderPage>, adjacentPageCount: Int,
    ): List<IosReaderPage> = ReaderChapterWindow.pages(previous, current, next, adjacentPageCount)

    /** Retains measured dimensions by identity when adjacent pages prepend or the active chapter changes. */
    fun setPages(pages: List<IosReaderPage>) {
        assignPages(pages)
        rebuild()
    }

    /**
     * Replaces a continuous strip's pages in place, like Android's webtoon window expansion
     * (`VerticalReaderScene.updatePages`): the page under the viewport at ([viewportX], [viewportY]) keeps its place
     * on screen, and the returned viewport is where the strip must now scroll to show it there. Null when there was no
     * strip to keep (paged modes, or nothing laid out yet): the caller restores its reading position instead.
     */
    fun setPages(pages: List<IosReaderPage>, viewportX: Float, viewportY: Float): IosReaderViewport? {
        val current = scene
        if (mode < 3 || current == null || current.pageCount == 0 || current is PagedReaderScene) {
            setPages(pages)
            return null
        }
        assignPages(pages)
        specLookup.clear()
        val compensation = current.updatePages(sceneHints(), viewport(viewportX, viewportY))
        indexScene()
        val bounds = compensation?.compensatedViewport?.bounds ?: return null
        return IosReaderViewport(bounds.left, bounds.top)
    }

    private fun assignPages(pages: List<IosReaderPage>) {
        val measured = pageKeys.mapIndexedNotNull { index, key -> sizes[index]?.let { key to it } }.toMap()
        sizes.clear()
        pages.forEachIndexed { index, key -> measured[key]?.let { sizes[index] = it } }
        pageKeys = pages.toList()
        count = pages.size
    }

    private fun sceneHints(): List<Pair<PageId, PageGeometryHint>> {
        val unknown = unknownRatio?.takeIf { mode >= 3 }?.let { PageGeometryHint.AspectRatio(it) }
            ?: PageGeometryHint.Estimated(0.7f)
        return (0 until count).map { sceneId(it) to (sizes[it] ?: unknown) }
    }

    private fun viewport(x: Float, y: Float) =
        ReaderViewport(FloatRect.fromLtwh(x, y, width.toFloat(), height.toFloat()))

    /** The scene id of window position [index]. */
    private fun sceneId(index: Int): PageId {
        if (mode < 3) return PageId(index.toLong())
        val key = pageKeys.getOrNull(index) ?: return PageId(index.toLong())
        return PageId(stableIds.getOrPut(key) { nextStableId++ })
    }

    /** The window position of a scene page id (the page itself for a split half). */
    private fun windowIndex(id: PageId): Int =
        if (mode >= 3) indexById[id.value] ?: id.value.toInt() else specLookup[id.value]?.first ?: id.value.toInt()

    private fun indexScene() {
        layoutGeneration++
        indexById.clear()
        if (mode >= 3) for (index in 0 until count) indexById[sceneId(index).value] = index
    }

    /**
     * Zooms a continuous strip by laying it out again at [zoom] × the viewport's cross axis, so pages decode and tile
     * for the size they are shown at and the scroll view pans the extra width natively. Offsets in [progress] and
     * [scrollOrigin] stay in unzoomed pixels, so saved positions do not depend on the zoom. Paged modes ignore it.
     */
    fun setZoom(zoom: Float) {
        val value = zoom.coerceIn(MINIMUM_ZOOM, MAXIMUM_ZOOM)
        if (value == this.zoom) return
        this.zoom = value
        if (mode >= 3) rebuild()
    }

    fun reset() {
        layoutGeneration++
        ratioEstimator = ReaderChapterRatioEstimator(); unknownRatio = null
        sizes.clear(); specLookup.clear(); pageKeys = emptyList(); scene = null; count = 0
        stableIds.clear(); indexById.clear()
    }

    fun setImageSize(index: Int, width: Int, height: Int) {
        updateImageSize(index, width, height, null)
    }

    /** Returns the compensated continuous viewport when a late image size changes the strip layout. */
    fun setImageSize(index: Int, width: Int, height: Int, viewportX: Float, viewportY: Float): IosReaderViewport? =
        updateImageSize(index, width, height, viewport(viewportX, viewportY))

    private fun updateImageSize(index: Int, width: Int, height: Int, viewport: ReaderViewport?): IosReaderViewport? {
        if (index !in 0 until count || width <= 0 || height <= 0) return null
        val hint = PageGeometryHint.Exact(width, height)
        if (sizes[index] == hint) return null
        sizes[index] = hint
        if (splitWide && !doublePage && mode < 3) {
            rebuild()
        } else {
            val current = scene
            val compensation = current?.updatePageHint(sceneId(index), hint, viewport)
            var compensated = compensation?.compensatedViewport ?: viewport
            // The chapter's ratio moved: every unknown page takes it at once, anchored like the size change itself.
            val ratio = if (mode >= 3) ratioEstimator.onDecoded(width, height) else null
            if (current != null && ratio != null) {
                unknownRatio = ratio
                val relayout = current.updatePages(sceneHints(), compensated)
                compensated = relayout?.compensatedViewport ?: compensated
                if (relayout == null && compensation == null) return null
            } else if (compensation == null) {
                return null
            }
            if (mode >= 3 && compensated != null) {
                val bounds = compensated.bounds
                return IosReaderViewport(bounds.left, bounds.top)
            }
        }
        return null
    }

    /**
     * Whether a continuous page decoded at [width]×[height] (already at the size the strip shows it) is better drawn
     * as tiles than as one bitmap, by Android's `DecodePlanner`: one bitmap while it fits the renderer's 8192 px edge
     * and the 64 MB working set, since many tile textures cost far more to draw each frame than one sampled bitmap.
     */
    fun plansTiled(width: Int, height: Int, viewportWidth: Int, viewportHeight: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val plan = decodePlanner.plan(
            pageId = PageId(0),
            metadata = ImageSourceMetadata(IntSize(width, height)),
            viewportWidth = viewportWidth.coerceAtLeast(1),
            viewportHeight = viewportHeight.coerceAtLeast(1),
            capabilities = RendererCapabilities.Resolved(MAXIMUM_DIMENSION, MAXIMUM_DIMENSION),
        )
        return plan is DecodePlan.Tiled
    }

    /**
     * Android's reduced preload (`isPreloadReductionEnabled`): a smaller continuous resource window, for Low Power
     * Mode. Returns whether it changed, in which case the next plan starts over.
     */
    fun setPreloadReduction(enabled: Boolean): Boolean {
        if (enabled == preloadReduction) return false
        preloadReduction = enabled
        prediction = ReaderPrediction(if (enabled) REDUCED_PREDICTION else FULL_PREDICTION)
        return true
    }

    private fun rebuild() {
        layoutGeneration++
        specLookup.clear()
        val pages = sceneHints()
        val direction = if (mode == 1 || mode == 4 && horizontalReversed) SceneReadingDirection.RIGHT_TO_LEFT else
            if (mode == 2 || mode == 3) SceneReadingDirection.TOP_TO_BOTTOM else SceneReadingDirection.LEFT_TO_RIGHT
        scene = when (mode) {
            3 -> VerticalReaderScene(zoomed(width), height, pages)
            4 -> HorizontalReaderScene(zoomed(height), width, pages, readingDirection = direction)
            else -> {
                val specs = mutableListOf<PagedPageSpec>()
                val chapterIds = pageKeys.map { it.chapterId }.distinct().withIndex().associate { it.value to it.index.toLong() }
                pages.forEachIndexed { index, (id, hint) ->
                    val key = pageKeys.getOrNull(index)
                    val chapterId = key?.let { chapterIds.getValue(it.chapterId) } ?: 0L
                    val chapterIndex = key?.index ?: index
                    if (splitWide && !doublePage && hint is PageGeometryHint.Exact && WidePagePolicy.isWidePage(hint)) {
                        val halfHint = PageGeometryHint.Exact((hint.width / 2).coerceAtLeast(1), hint.height)
                        val firstSeg = if (mode == 1) PageSegment.RIGHT_HALF else PageSegment.LEFT_HALF
                        val secondSeg = if (mode == 1) PageSegment.LEFT_HALF else PageSegment.RIGHT_HALF
                        val firstCode = if (firstSeg == PageSegment.LEFT_HALF) 1 else 2
                        val secondCode = if (secondSeg == PageSegment.LEFT_HALF) 1 else 2
                        val secondId = PageId((count + index).toLong())
                        specLookup[id.value] = index to firstCode
                        specLookup[secondId.value] = index to secondCode
                        specs += PagedPageSpec(id, halfHint, chapterId, chapterIndex, segment = firstSeg)
                        specs += PagedPageSpec(secondId, halfHint, chapterId, chapterIndex, segment = secondSeg)
                    } else {
                        specLookup[id.value] = index to 0
                        specs += PagedPageSpec(id, hint, chapterId, chapterIndex)
                    }
                }
                PagedReaderScene(width, height,
                    PagedSpreadConfig(doublePage, cover, direction, zoomMode = when (fit) {
                        1 -> ZoomMode.FIT_WIDTH
                        2 -> ZoomMode.FIT_HEIGHT
                        3 -> ZoomMode.KEEP_START
                        else -> ZoomMode.FIT_CENTER
                    }),
                    specs)
            }
        }
        indexScene()
    }

    private fun zoomed(extent: Int): Int = (extent * zoom).toInt().coerceAtLeast(1)

    fun slots(): List<IosReaderSlot> {
        val current = scene ?: return emptyList()
        return if (current is PagedReaderScene) current.allSlots.map { slot ->
            val anchor = specLookup[slot.progressAnchorPageId.value]?.first ?: slot.progressAnchorPageId.value.toInt()
            IosReaderSlot(slot.slotIndex, anchor, slot.placements.map {
                val b = it.boundsInSlot
                val (pageIndex, segment) = specLookup[it.pageId.value] ?: (it.pageId.value.toInt() to 0)
                IosReaderPlacement(pageIndex, b.left, b.top, b.width, b.height, segment)
            })
        } else current.pageGeometries.mapIndexed { index, geometry ->
            val b = geometry.sceneBounds
            IosReaderSlot(index, windowIndex(geometry.pageId), listOf(
                IosReaderPlacement(index, b.left, b.top, b.width, b.height, 0)))
        }
    }

    /**
     * Each continuous slot's row for the host's per-frame lookups, as 32-bit floats in one buffer instead of an object
     * per slot and placement: slot index, anchor, placement count, then the first placement's x, y, width, height,
     * page index and segment ([SLOT_TABLE_COLUMNS] values per slot, in [slots] order).
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun slotTable(): NSData {
        val current = scene ?: return NSData()
        val table: FloatArray
        if (current is PagedReaderScene) {
            // Few slots, several placements each: built from the same slots the host holds.
            val slots = slots()
            table = FloatArray(slots.size * SLOT_TABLE_COLUMNS)
            slots.forEachIndexed { row, slot ->
                val first = slot.placements.firstOrNull()
                writeRow(table, row, slot.index, slot.anchor, slot.placements.size, first?.x ?: 0f, first?.y ?: 0f,
                    first?.width ?: 0f, first?.height ?: 0f, first?.index ?: -1, first?.segment ?: 0)
            }
        } else {
            // A strip's slot is its page: written straight from the scene, without a slot object per page.
            val geometries = current.pageGeometries
            table = FloatArray(geometries.size * SLOT_TABLE_COLUMNS)
            geometries.forEachIndexed { index, geometry ->
                val b = geometry.sceneBounds
                writeRow(table, index, index, windowIndex(geometry.pageId), 1, b.left, b.top, b.width, b.height, index, 0)
            }
        }
        if (table.isEmpty()) return NSData()
        return table.usePinned { NSData.create(bytes = it.addressOf(0), length = (table.size * 4).convert()) }
    }

    private fun writeRow(
        table: FloatArray, row: Int, index: Int, anchor: Int, count: Int,
        x: Float, y: Float, width: Float, height: Float, page: Int, segment: Int,
    ) {
        val base = row * SLOT_TABLE_COLUMNS
        table[base] = index.toFloat(); table[base + 1] = anchor.toFloat(); table[base + 2] = count.toFloat()
        table[base + 3] = x; table[base + 4] = y; table[base + 5] = width; table[base + 6] = height
        table[base + 7] = page.toFloat(); table[base + 8] = segment.toFloat()
    }

    private fun resolveFrame(x: Float, y: Float): ReaderFrame? {
        val current = scene ?: return null
        val viewport = viewport(x, y)
        val cached = resolvedFrame
        if (cached != null && resolvedScene === current && resolvedRevision == current.revision &&
            resolvedGeneration == layoutGeneration && resolvedViewport == viewport) {
            return cached
        }
        return current.resolve(viewport).also {
            resolvedScene = current; resolvedRevision = current.revision; resolvedGeneration = layoutGeneration
            resolvedViewport = viewport
            resolvedFrame = it
        }
    }

    fun progress(x: Float, y: Float): IosReaderProgress {
        val frame = resolveFrame(x, y)
        val pageIndex = frame?.progress?.activePageId?.let(::windowIndex) ?: 0
        return IosReaderProgress(pageIndex, (frame?.progress?.intraPageOffsetPx ?: 0f) / continuousZoom)
    }

    /** The last page in reading order that the viewport at ([x], [y]) shows, or -1 when it shows none. */
    fun lastVisible(x: Float, y: Float): Int {
        val frame = resolveFrame(x, y)
        return frame?.progress?.lastVisiblePageId?.let(::windowIndex) ?: -1
    }

    /** [progress] and [lastVisible] of one viewport in one call, for the strip's scroll frames. */
    fun position(x: Float, y: Float): IosReaderPosition {
        val progress = progress(x, y)
        return IosReaderPosition(progress.index, progress.offset, lastVisible(x, y))
    }

    /**
     * The continuous strip's resource window for the viewport at ([x], [y]) moving at [velocityX]/[velocityY] px/s
     * (physical, positive towards larger offsets), from the shared prediction Android's webtoon uses: pages on screen,
     * then the lookahead that grows with the fling. Null when the window is the one planned last (pixel scrolling
     * within the same pages), unless [force]. Paged modes have none.
     */
    fun resourceWindow(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float,
        dragging: Boolean,
        force: Boolean,
    ): List<IosReaderRequest>? {
        val current = scene ?: return null
        if (mode < 3 || current is PagedReaderScene) return null
        val frame = resolveFrame(x, y) ?: return null
        val motion = ViewportMotion(velocityX, velocityY, dragging)
        val window = prediction.predictWindowIfChanged(current, frame, motion, force) ?: return null
        return window.requests.mapNotNull { request ->
            val index = windowIndex(request.pageId)
            if (index !in 0 until count) return@mapNotNull null
            IosReaderRequest(
                index = index,
                priority = when (request.priority) {
                    PrefetchPriority.IMMEDIATE -> 0
                    PrefetchPriority.HIGH -> 1
                    else -> 2
                },
                decode = request.readiness == PrefetchReadiness.PRESENTATION_READY,
            )
        }
    }

    fun decodeSize(width: Int, height: Int): IosReaderSize {
        val resolved = ImageDecodeSize.resolve(IntSize(width, height), MAXIMUM_PIXELS, MAXIMUM_DIMENSION)
        return IosReaderSize(resolved.width, resolved.height)
    }

    fun isTiled(width: Int, height: Int): Boolean =
        width > 4096 || height > 4096 || width.toLong() * height > MAXIMUM_PIXELS

    fun edgeSampleSize(width: Int, height: Int): Int =
        ReaderEdgeDetection.sampleSize(width, height)

    fun contentBounds(
        width: Int,
        height: Int,
        sampleSize: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pixels: IntArray,
    ): IosReaderRect? {
        if (width <= 0 || height <= 0 || sampleSize <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (pixels.size < bitmapWidth * bitmapHeight) return null
        val rect = ReaderEdgeDetection.contentBounds(width, height, sampleSize, bitmapWidth, bitmapHeight) { out, x, y, w, h ->
            for (row in 0 until h) {
                val srcOffset = (y + row) * bitmapWidth + x
                val dstOffset = row * w
                pixels.copyInto(out, destinationOffset = dstOffset, startIndex = srcOffset, endIndex = srcOffset + w)
            }
        } ?: return null
        return IosReaderRect(rect.left, rect.top, rect.width, rect.height)
    }

    fun visibleTiles(
        x: Float,
        y: Float,
        index: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        displayScale: Float,
    ): List<IosReaderTile> = visibleTiles(x, y, index, encodedWidth, encodedHeight, displayScale, 1f)

    /** Pixel density affects sampling only; camera zoom independently determines the visible source region. */
    fun visibleTiles(
        x: Float,
        y: Float,
        index: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        displayScale: Float,
        zoomScale: Float,
    ): List<IosReaderTile> {
        if (index !in 0 until count || encodedWidth <= 0 || encodedHeight <= 0) return emptyList()
        val current = scene ?: return emptyList()
        val density = displayScale.coerceAtLeast(1f)
        val cameraScale = zoomScale.coerceAtLeast(1f)
        val node = if (current is PagedReaderScene) {
            val slot = current.allSlots.firstOrNull { s ->
                s.placements.any { (specLookup[it.pageId.value]?.first ?: it.pageId.value.toInt()) == index }
           }
            slot?.visibleContentNodes(cameraScale, x, y)?.firstOrNull {
                (specLookup[it.pageId.value]?.first ?: it.pageId.value.toInt()) == index
            }
        } else {
            val id = sceneId(index)
            current.resolve(viewport(x, y)).visibleNodes.firstOrNull { it.pageId == id }
        } ?: return emptyList()
        val pixelScale = density * if (current is PagedReaderScene) cameraScale else 1f
        return VisibleImageTiles.resolve(node, IntSize(encodedWidth, encodedHeight), pixelScale).map { spec ->
            IosReaderTile(
                row = spec.key.row,
                col = spec.key.col,
                sampleSize = spec.sampleSize,
                logicalLeft = spec.logicalRect.left,
                logicalTop = spec.logicalRect.top,
                logicalWidth = spec.logicalRect.width,
                logicalHeight = spec.logicalRect.height,
                decodeLeft = spec.decodeRegion.left,
                decodeTop = spec.decodeRegion.top,
                decodeWidth = spec.decodeRegion.width,
                decodeHeight = spec.decodeRegion.height,
            )
        }
    }

    /**
     * The turn of [slot] while the pager rests on or travels from [anchorSlot] to [position] (slots in reading order).
     * [style] is Android's `ReaderAnimation` ordinal (`NONE`, `DEFAULT`, `ADVANCED`, `SIMULATION`); [mirrored] is
     * right-to-left reading, where pages fold from the physical left edge. [downY] is where the finger went down, as a
     * fraction of the page across the paging axis.
     */
    fun pageTurn(
        style: Int,
        slot: Int,
        position: Float,
        anchorSlot: Int,
        scrolling: Boolean,
        width: Float,
        height: Float,
        vertical: Boolean,
        mirrored: Boolean,
        downY: Float = 0.5f,
    ): IosPageTurn {
        val turn = when (style) {
            2 -> PageTurnStyle.COVER
            3 -> PageTurnStyle.CURL
            else -> return IosPageTurn(0f, 1f, 0f, false)
        }
        val nearest = kotlin.math.round(position).toInt()
        val motion = PagedMotionSnapshot(
            currentSlot = nearest, settledSlot = anchorSlot, targetSlot = nearest,
            offsetFraction = position - nearest, isScrollInProgress = scrolling,
        )
        // Reading-order semantics: SwiftUI mirrors the pager for right-to-left reading itself.
        val input = PagedTransitionResolver.resolve(motion, slot, SceneReadingDirection.LEFT_TO_RIGHT)
        val transform = PageTurnTransition.transformFor(turn, input, vertical, isReversed = false)
        val shift = when (turn) {
            PageTurnStyle.COVER -> if (PageTurnTransition.activeCoverPinnedSlot(motion) == slot) -input.pageOffset else 0f
            else -> transform.translationFactor
        }
        val geometry = PageTurnTransition.curlGeometry(
            width = width,
            height = height,
            transform = transform,
            downFraction = if (vertical) PagePoint(downY, 0.5f) else PagePoint(0.5f, downY),
            isVertical = vertical,
            isReversed = mirrored && !vertical,
        ) ?: return IosPageTurn(shift, transform.alpha, transform.revealedPageShade, false)
        val front = geometry.frontPath
        return IosPageTurn(
            shift = shift,
            alpha = transform.alpha,
            shade = transform.revealedPageShade,
            folding = true,
            topX = geometry.topCurlOffset.x,
            topY = geometry.topCurlOffset.y,
            bottomX = geometry.bottomCurlOffset.x,
            bottomY = geometry.bottomCurlOffset.y,
            // The front polygon starts at the page corner the fold travels towards; its centre is always kept.
            keepX = front.sumOf { it.x.toDouble() }.toFloat() / front.size,
            keepY = front.sumOf { it.y.toDouble() }.toFloat() / front.size,
        )
    }

    fun clampPan(value: Float, contentMin: Float, contentMax: Float, viewport: Float, scale: Float): Float =
        value.coerceIn(PagedPanBoundsResolver.resolvePanRange(contentMin, contentMax, viewport, scale.coerceAtLeast(1f)))

    fun autoScrollDelay(speed: Float): Long = ReaderAutoScroll.pageSwitchDelayMs(speed)

    fun scrollOrigin(index: Int, offset: Float): Float = scene?.resolveViewportOriginForPage(
        sceneId(index), if (mode == 3) height.toFloat() else width.toFloat(), offset * continuousZoom,
    )?.coerceIn(0f, scrollLimit()) ?: 0f

    fun scrollLimit(): Float = ((scene?.totalSceneExtent ?: 0f) - if (mode == 3) height else width).coerceAtLeast(0f)

    private val continuousZoom: Float get() = if (mode >= 3) zoom else 1f

    fun autoScrollDistance(speed: Float, elapsedMillis: Int): Float =
        elapsedMillis.coerceAtLeast(0).toFloat() * ReaderAutoScroll.speedMultiplier(speed) / 32f

    companion object {
        const val MAXIMUM_PIXELS = 4L * 1024 * 1024
        const val SLOT_TABLE_COLUMNS = 9
        /** Android's webtoon resource window (`ComposeSceneWebtoonReader`'s default `ReaderPredictionConfig`). */
        private val FULL_PREDICTION = ReaderPredictionConfig(
            staticAheadFraction = 3.0f,
            staticBehindFraction = 1.5f,
            lookaheadHorizonSeconds = 0.8f,
            maxLookaheadExtraPx = 8000f,
        )
        /** Its reduced form (`isPreloadReductionEnabled`). */
        private val REDUCED_PREDICTION = ReaderPredictionConfig(
            staticAheadFraction = 1.0f,
            staticBehindFraction = 0.5f,
            lookaheadHorizonSeconds = 0.4f,
            maxLookaheadExtraPx = 3000f,
        )
        /** Above any window position and split-half id, so a stable id never names a position. */
        private const val STABLE_ID_BASE = 1L shl 40
        const val MAXIMUM_DIMENSION = 16384
        const val MINIMUM_ZOOM = 0.5f
        const val MAXIMUM_ZOOM = 4f
        /** Paged slots zoom as far as the shared camera allows (Android's paged reader, 5×). */
        const val MAXIMUM_PAGE_ZOOM = PagedCameraTransform.MAX_SCALE
        /** Android's double tap zooms an unzoomed page to 2× (`READER_DOUBLE_TAP_ZOOM_SCALE`). */
        const val DOUBLE_TAP_ZOOM = 2f
    }
}
