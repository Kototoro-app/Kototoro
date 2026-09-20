package org.skepsun.kototoro.reader.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * High-performance pure-Kotlin Reader Scene for paged reading modes (Single Page, Double Page, RTL Manga).
 *
 * Implements [MutableReaderScene] (Invariant I1: zero Android/Compose dependencies).
 *
 * Features:
 * - Grouped into discrete [PagedSlot]s via [PagedSpreadResolver].
 * - Support for both Single-Page and Double-Page spreads.
 * - Chapter isolation & wide page solo constraints.
 * - Direction-aware page placement (LTR, RTL, Vertical).
 * - Zero-CLS anchor compensation on geometry changes.
 * - O(1) range location + O(k) candidate enumeration for viewport queries, built on the
 *   fixed slot primary extent; exact intersection semantics are preserved (improvement
 *   plan 2026-09 §6.1).
 */
class PagedReaderScene(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val config: PagedSpreadConfig = PagedSpreadConfig(),
    initialSpecs: List<PagedPageSpec> = emptyList(),
) : MutableReaderScene {

    private val specs = ArrayList<PagedPageSpec>()
    private val slots = ArrayList<PagedSlot>()

    /** PageId -> index in [specs]; first occurrence wins, matching the linear scan. */
    private val pageIndexById = HashMap<PageId, Int>()

    /** PageId -> slot index; first occurrence wins, matching the linear scan. */
    private val slotIndexByPageId = HashMap<PageId, Int>()

    /**
     * True when every slot's primary-axis window starts at `index * primaryStride`.
     * [PagedSpreadResolver] always lays slots out on the fixed viewport stride; the flag is
     * re-verified on every rebuild so a future layout change degrades the range queries to
     * a full linear scan instead of silently missing slots.
     */
    private var slotsOnUniformStride = true

    override val readingDirection: SceneReadingDirection get() = config.readingDirection

    val availableWidth: Int get() = viewportWidth
    val pageSpacingPx: Int get() = config.pageSpacingPx

    override val totalSceneExtent: Float
        get() = if (readingDirection.isHorizontal) {
            slots.size * viewportWidth.toFloat()
        } else {
            slots.size * viewportHeight.toFloat()
        }

    override var revision: Long = 0L
        private set

    override val pageCount: Int get() = specs.size
    val slotCount: Int get() = slots.size

    val allSlots: List<PagedSlot> get() = slots

    override val pageGeometries: List<PageGeometry>
        get() = slots.flatMap { slot ->
            slot.placements.map { PageGeometry(it.pageId, it.sceneBounds) }
        }

    /** Primary-axis extent occupied by each slot: the fixed viewport stride. */
    private val primaryStride: Float
        get() = if (readingDirection.isHorizontal) viewportWidth.toFloat() else viewportHeight.toFloat()

    init {
        if (initialSpecs.isNotEmpty()) {
            setPagedPages(initialSpecs)
        }
    }

    fun setPagedPages(newSpecs: List<PagedPageSpec>) {
        specs.clear()
        specs.addAll(newSpecs)
        rebuildSlots()
        revision++
    }

    override fun setPages(pages: List<Pair<PageId, PageGeometryHint>>) {
        val converted = pages.mapIndexed { index, (id, hint) ->
            PagedPageSpec(
                pageId = id,
                geometryHint = hint,
                chapterId = 0L,
                chapterPageIndex = index,
            )
        }
        setPagedPages(converted)
    }

    private fun rebuildSlots() {
        slots.clear()
        slots.addAll(
            PagedSpreadResolver.resolveSlots(
                specs = specs,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                config = config,
            ),
        )
        slotsOnUniformStride = verifyUniformStride()
        rebuildPageIndices()
    }

    /**
     * Verifies the layout invariant the O(1) range queries rely on: slot i's primary window
     * starts at `i * primaryStride`. The tolerance only absorbs float accumulation drift in
     * pathologically long scenes; real layouts are exact.
     */
    private fun verifyUniformStride(): Boolean {
        if (slots.isEmpty()) return true
        val stride = primaryStride
        if (stride <= 0f) return false
        val isHorizontal = readingDirection.isHorizontal
        val tolerance = minOf(0.5f, stride * 0.05f)
        for (i in slots.indices) {
            val start = if (isHorizontal) slots[i].bounds.left else slots[i].bounds.top
            if (abs(start - i * stride) > tolerance) return false
        }
        return true
    }

    private fun rebuildPageIndices() {
        pageIndexById.clear()
        specs.forEachIndexed { index, spec -> pageIndexById.putIfAbsent(spec.pageId, index) }
        slotIndexByPageId.clear()
        for (slotIndex in slots.indices) {
            for (placement in slots[slotIndex].placements) {
                slotIndexByPageId.putIfAbsent(placement.pageId, slotIndex)
            }
        }
    }

    override fun indexOf(pageId: PageId): Int = pageIndexById[pageId] ?: -1

    fun slotIndexOf(pageId: PageId): Int = slotIndexByPageId[pageId] ?: -1

    override fun resolvePageScrollPosition(pageId: PageId): Float? {
        val slotIndex = slotIndexOf(pageId)
        if (slotIndex < 0) return null
        val primaryExtent = if (readingDirection.isHorizontal) viewportWidth.toFloat() else viewportHeight.toFloat()
        return slotIndex * primaryExtent
    }

    override fun resolveViewportOriginForPage(
        pageId: PageId,
        viewportExtent: Float,
        intraPageOffset: Float,
    ): Float? {
        val pos = resolvePageScrollPosition(pageId) ?: return null
        return pos + intraPageOffset
    }

    override fun updatePages(
        newPages: List<Pair<PageId, PageGeometryHint>>,
        currentViewport: ReaderViewport?,
    ): AnchorCompensation? {
        val activeAnchor = currentViewport?.let { resolveActivePageId(it) }
        val oldOrigin = activeAnchor?.let { resolvePageScrollPosition(it) }

        setPages(newPages)

        if (currentViewport == null || activeAnchor == null || oldOrigin == null) return null
        val newOrigin = resolvePageScrollPosition(activeAnchor) ?: return null

        val delta = newOrigin - oldOrigin
        val deltaX = if (readingDirection.isHorizontal) delta else 0f
        val deltaY = if (readingDirection.isVertical) delta else 0f

        val newBounds = currentViewport.bounds.translate(deltaX, deltaY)
        return AnchorCompensation(
            deltaX = deltaX,
            deltaY = deltaY,
            compensatedViewport = currentViewport.copy(bounds = newBounds),
        )
    }

    fun updatePagedPages(
        newSpecs: List<PagedPageSpec>,
        currentViewport: ReaderViewport?,
    ): AnchorCompensation? {
        val activeAnchor = currentViewport?.let { resolveActivePageId(it) }
        val oldOrigin = activeAnchor?.let { resolvePageScrollPosition(it) }

        setPagedPages(newSpecs)

        if (currentViewport == null || activeAnchor == null || oldOrigin == null) return null
        val newOrigin = resolvePageScrollPosition(activeAnchor) ?: return null

        val delta = newOrigin - oldOrigin
        val deltaX = if (readingDirection.isHorizontal) delta else 0f
        val deltaY = if (readingDirection.isVertical) delta else 0f

        val newBounds = currentViewport.bounds.translate(deltaX, deltaY)
        return AnchorCompensation(
            deltaX = deltaX,
            deltaY = deltaY,
            compensatedViewport = currentViewport.copy(bounds = newBounds),
        )
    }

    override fun updatePageHint(
        pageId: PageId,
        newHint: PageGeometryHint,
        currentViewport: ReaderViewport?,
    ): AnchorCompensation? {
        val index = indexOf(pageId)
        if (index < 0) return null

        val activeAnchor = currentViewport?.let { resolveActivePageId(it) }
        val oldOrigin = activeAnchor?.let { resolvePageScrollPosition(it) }

        val oldSpec = specs[index]
        specs[index] = oldSpec.copy(geometryHint = newHint)
        rebuildSlots()
        revision++

        if (currentViewport == null || activeAnchor == null || oldOrigin == null) return null
        val newOrigin = resolvePageScrollPosition(activeAnchor) ?: return null

        val delta = newOrigin - oldOrigin
        val deltaX = if (readingDirection.isHorizontal) delta else 0f
        val deltaY = if (readingDirection.isVertical) delta else 0f

        val newBounds = currentViewport.bounds.translate(deltaX, deltaY)
        return AnchorCompensation(
            deltaX = deltaX,
            deltaY = deltaY,
            compensatedViewport = currentViewport.copy(bounds = newBounds),
        )
    }

    /**
     * Base candidate range computation shared by draw, transition-fixed pages and resource
     * prefetch (improvement plan §6.1): O(1) index arithmetic on the fixed slot primary
     * extent. The returned range is a conservative superset — every slot whose bounds
     * positively intersect [bounds] in both axes lies inside it — and consumers keep their
     * own exact intersection judgment.
     *
     * If the layout ever stops being uniformly strided, the range degrades to the full slot
     * span (a linear scan, i.e. the pre-optimization behaviour) rather than missing slots.
     */
    fun slotIndexRangeFor(bounds: FloatRect): IntRange {
        val count = slots.size
        if (count == 0) return IntRange.EMPTY
        if (!slotsOnUniformStride) return 0 until count
        val stride = primaryStride
        if (stride <= 0f) return IntRange.EMPTY
        val isHorizontal = readingDirection.isHorizontal
        val viewportMin = if (isHorizontal) bounds.left else bounds.top
        val viewportMax = if (isHorizontal) bounds.right else bounds.bottom
        // One stride of low margin absorbs the verified layout tolerance; the high end needs
        // none because slot extents are exactly the stride.
        val first = floor(viewportMin / stride).toInt() - 1
        val last = ceil(viewportMax / stride).toInt()
        val low = first.coerceIn(0, count - 1)
        val high = last.coerceIn(0, count - 1)
        return if (low <= high) low..high else IntRange.EMPTY
    }

    /**
     * Slots whose bounds positively intersect [bounds] (both axes, non-degenerate area),
     * in slot order — the draw-phase visible set. O(k) in the number of intersecting slots;
     * the exact geometric judgment is kept rather than assuming a two-page window.
     */
    fun slotsIntersecting(bounds: FloatRect): List<PagedSlot> {
        if (slots.isEmpty()) return emptyList()
        val result = ArrayList<PagedSlot>(2)
        for (i in slotIndexRangeFor(bounds)) {
            val slot = slots[i]
            val intersect = slot.bounds.intersectionOrNull(bounds)
            if (intersect != null && intersect.width > 0f && intersect.height > 0f) {
                result.add(slot)
            }
        }
        return result
    }

    override fun resolveActivePageId(viewport: ReaderViewport): PageId? {
        val activeSlot = resolveActiveSlot(viewport) ?: return null
        return activeSlot.progressAnchorPageId
    }

    private fun resolveActiveSlot(viewport: ReaderViewport): PagedSlot? {
        if (slots.isEmpty()) return null
        val isHorizontal = readingDirection.isHorizontal
        val vpCenter = if (isHorizontal) {
            viewport.bounds.left + viewport.bounds.width / 2f
        } else {
            viewport.bounds.top + viewport.bounds.height / 2f
        }

        if (!slotsOnUniformStride) {
            var closestSlot: PagedSlot? = null
            var minDistance = Float.MAX_VALUE

            for (slot in slots) {
                val slotCenter = if (isHorizontal) {
                    slot.bounds.left + slot.bounds.width / 2f
                } else {
                    slot.bounds.top + slot.bounds.height / 2f
                }
                val distance = abs(vpCenter - slotCenter)
                if (distance < minDistance) {
                    minDistance = distance
                    closestSlot = slot
                }
            }
            return closestSlot
        }

        // Nearest-center semantics with O(1) index arithmetic: the distance to slot centers
        // is V-shaped in the slot index, so the minimizer is one of the two slots bracketing
        // vpCenter / stride - 0.5. Distances are still measured from the actual slot bounds,
        // and ascending iteration with a strict less-than keeps the tie rule (lower slot
        // wins) identical to the linear scan.
        val stride = primaryStride
        val lower = floor(vpCenter / stride - 0.5f).toInt()

        var closestSlot: PagedSlot? = null
        var minDistance = Float.MAX_VALUE
        var bestIndex = -1
        for (rawIndex in lower..lower + 1) {
            val index = rawIndex.coerceIn(0, slots.size - 1)
            if (index == bestIndex) continue
            val slot = slots[index]
            val slotCenter = if (isHorizontal) {
                slot.bounds.left + slot.bounds.width / 2f
            } else {
                slot.bounds.top + slot.bounds.height / 2f
            }
            val distance = abs(vpCenter - slotCenter)
            if (distance < minDistance) {
                minDistance = distance
                closestSlot = slot
                bestIndex = index
            }
        }
        return closestSlot
    }

    override fun resolve(viewport: ReaderViewport): ReaderFrame {
        if (slots.isEmpty()) {
            return ReaderFrame(
                viewport = viewport,
                visibleNodes = emptyList(),
                progress = ReaderProgressSnapshot(
                    lowerPageId = null,
                    upperPageId = null,
                    activePageId = null,
                    intraPageOffsetPx = 0f,
                ),
            )
        }

        val visibleNodes = mutableListOf<VisibleNode>()
        var activeSlot: PagedSlot? = null
        var maxIntersectArea = -1f

        val isHorizontal = readingDirection.isHorizontal

        for (i in slotIndexRangeFor(viewport.bounds)) {
            val slot = slots[i]
            val slotIntersect = slot.bounds.intersectionOrNull(viewport.bounds)
            if (slotIntersect != null && slotIntersect.width > 0f && slotIntersect.height > 0f) {
                val area = slotIntersect.width * slotIntersect.height
                if (area > maxIntersectArea) {
                    maxIntersectArea = area
                    activeSlot = slot
                }

                for (placement in slot.placements) {
                    val pageIntersect = placement.sceneBounds.intersectionOrNull(viewport.bounds)
                    if (pageIntersect != null && pageIntersect.width > 0f && pageIntersect.height > 0f) {
                        visibleNodes.add(
                            VisibleNode(
                                pageId = placement.pageId,
                                sceneBounds = placement.sceneBounds,
                                visibleRegion = pageIntersect,
                            ),
                        )
                    }
                }
            }
        }

        val resolvedActiveSlot = activeSlot ?: resolveActiveSlot(viewport)
        val activeAnchorId = resolvedActiveSlot?.progressAnchorPageId
        val intraOffset = if (resolvedActiveSlot != null) {
            if (isHorizontal) {
                viewport.bounds.left - resolvedActiveSlot.bounds.left
            } else {
                viewport.bounds.top - resolvedActiveSlot.bounds.top
            }
        } else 0f

        val firstVisiblePageId = visibleNodes.firstOrNull()?.pageId ?: activeAnchorId
        val lastVisiblePageId = visibleNodes.lastOrNull()?.pageId ?: activeAnchorId

        return ReaderFrame(
            viewport = viewport,
            visibleNodes = visibleNodes,
            progress = ReaderProgressSnapshot(
                lowerPageId = firstVisiblePageId,
                upperPageId = lastVisiblePageId,
                activePageId = activeAnchorId,
                intraPageOffsetPx = intraOffset,
            ),
        )
    }
}
