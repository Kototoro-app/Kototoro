package org.skepsun.kototoro.reader.core

import kotlin.math.abs

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
 */
class PagedReaderScene(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val config: PagedSpreadConfig = PagedSpreadConfig(),
    initialSpecs: List<PagedPageSpec> = emptyList(),
) : MutableReaderScene {

    private val specs = ArrayList<PagedPageSpec>()
    private val slots = ArrayList<PagedSlot>()

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
    }

    override fun indexOf(pageId: PageId): Int {
        for (i in specs.indices) {
            if (specs[i].pageId == pageId) return i
        }
        return -1
    }

    fun slotIndexOf(pageId: PageId): Int {
        for (i in slots.indices) {
            if (slots[i].containsPage(pageId)) return i
        }
        return -1
    }

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

        for (slot in slots) {
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
