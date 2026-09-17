package org.skepsun.kototoro.reader.core

/**
 * Continuous vertical 2D scene for webtoon reading.
 *
 * Encapsulates the production-proven layout rules from [measureWebtoonViewport] and active page
 * resolution from [resolveLastEndVisibleWebtoonPageKey] into a pure, platform-agnostic model.
 *
 * Implements ADR 0002 Constraint 1:
 * - Decouples scene layout from image decoding status.
 * - Provides [updatePageHint] with [AnchorCompensation] to ensure zero perceptual jump when
 *   estimated dimensions are updated with exact dimensions.
 */
class VerticalReaderScene(
    val availableWidth: Int,
    val defaultViewportHeight: Int,
    initialPages: List<Pair<PageId, PageGeometryHint>> = emptyList(),
    val pageSpacingPx: Int = 0,
) : MutableReaderScene {
    init {
        require(availableWidth > 0) { "availableWidth must be > 0: $availableWidth" }
        require(defaultViewportHeight > 0) { "defaultViewportHeight must be > 0: $defaultViewportHeight" }
        require(pageSpacingPx >= 0) { "pageSpacingPx must be >= 0: $pageSpacingPx" }
    }

    override val readingDirection: SceneReadingDirection get() = SceneReadingDirection.TOP_TO_BOTTOM

    private val entries = ArrayList<PageEntry>(initialPages.size)

    /** Ordered list of laid-out page geometries. */
    override val pageGeometries: List<PageGeometry> get() = entries.map { it.geometry }

    /** Total height of the continuous scene in pixels. */
    var totalSceneHeight: Float = 0f
        private set

    override val totalSceneExtent: Float get() = totalSceneHeight

    /** Monotonic geometry revision used to invalidate derived resource windows. */
    override var revision: Long = 0L
        private set

    override val pageCount: Int get() = entries.size

    init {
        if (initialPages.isNotEmpty()) {
            setPages(initialPages)
        }
    }

    private data class PageEntry(
        val pageId: PageId,
        var hint: PageGeometryHint,
        var geometry: PageGeometry,
    )

    /**
     * Fully replaces the current pages with a new page list (e.g. chapter window load or replacement).
     */
    override fun setPages(pages: List<Pair<PageId, PageGeometryHint>>) {
        entries.clear()
        entries.ensureCapacity(pages.size)
        var currentY = 0f
        for (i in pages.indices) {
            val (pageId, hint) = pages[i]
            val pageHeight = computePageHeight(hint)
            val bounds = FloatRect.fromLtwh(0f, currentY, availableWidth.toFloat(), pageHeight)
            val geometry = PageGeometry(pageId, bounds)
            entries.add(PageEntry(pageId, hint, geometry))
            currentY += pageHeight
            if (i < pages.size - 1) {
                currentY += pageSpacingPx.toFloat()
            }
        }
        totalSceneHeight = currentY
        revision++
    }

    /**
     * Updates the page list in the continuous scene, preserving exact geometry hints for pages
     * that already have them, and computing an [AnchorCompensation] to prevent perceptual jumps
     * when pages are prepended or appended (e.g. cross-chapter window expansions).
     *
     * @param newPages The updated sequence of page IDs and initial geometry hints.
     * @param currentViewport Optional current viewport, used to preserve the visual reading anchor.
     * @return [AnchorCompensation] with the required [deltaY] if an active anchor was preserved, or null.
     */
    override fun updatePages(
        newPages: List<Pair<PageId, PageGeometryHint>>,
        currentViewport: ReaderViewport?,
    ): AnchorCompensation? {
        if (newPages.isEmpty()) {
            entries.clear()
            totalSceneHeight = 0f
            revision++
            return null
        }

        val existingHints = entries.associate { it.pageId to it.hint }
        val activeAnchorId = currentViewport?.let { resolveActivePageId(it) }
        val oldAnchorTop = activeAnchorId?.let { resolvePageScrollPosition(it) }
        val oldIntraPageOffset = if (currentViewport != null && oldAnchorTop != null) {
            currentViewport.bounds.top - oldAnchorTop
        } else null

        entries.clear()
        entries.ensureCapacity(newPages.size)
        var currentY = 0f
        for (i in newPages.indices) {
            val (pageId, hint) = newPages[i]
            val existing = existingHints[pageId]
            val effectiveHint = if (existing is PageGeometryHint.Exact) existing else hint
            val pageHeight = computePageHeight(effectiveHint)
            val bounds = FloatRect.fromLtwh(0f, currentY, availableWidth.toFloat(), pageHeight)
            val geometry = PageGeometry(pageId, bounds)
            entries.add(PageEntry(pageId, effectiveHint, geometry))
            currentY += pageHeight
            if (i < newPages.size - 1) {
                currentY += pageSpacingPx.toFloat()
            }
        }
        totalSceneHeight = currentY
        revision++

        if (currentViewport == null || activeAnchorId == null || oldAnchorTop == null || oldIntraPageOffset == null) {
            return null
        }

        val newAnchorTop = resolvePageScrollPosition(activeAnchorId) ?: return null
        val targetViewportTop = newAnchorTop + oldIntraPageOffset
        val deltaY = targetViewportTop - currentViewport.bounds.top

        val newBounds = currentViewport.bounds.translate(0f, deltaY)
        return AnchorCompensation(
            deltaY = deltaY,
            compensatedViewport = currentViewport.copy(bounds = newBounds),
        )
    }

    /**
     * Returns the 0-indexed position of [pageId] in the scene, or -1 if absent.
     */
    override fun indexOf(pageId: PageId): Int {
        for (i in entries.indices) {
            if (entries[i].pageId == pageId) return i
        }
        return -1
    }

    /**
     * Resolves the top Y coordinate for [pageId] in scene coordinates, or null if absent.
     */
    override fun resolvePageScrollPosition(pageId: PageId): Float? {
        val index = indexOf(pageId)
        if (index < 0) return null
        return entries[index].geometry.sceneBounds.top
    }

    /**
     * Computes the visible nodes and active reading semantic state for a given [viewport].
     */
    override fun resolve(viewport: ReaderViewport): ReaderFrame {
        if (entries.isEmpty() || viewport.bounds.isEmpty) {
            return ReaderFrame(viewport, emptyList(), readingDirection)
        }
        val firstIdx = findFirstVisibleIndex(viewport.bounds.top)
        if (firstIdx < 0) {
            return ReaderFrame(viewport, emptyList(), readingDirection)
        }

        val visibleNodes = ArrayList<VisibleNode>(8)
        for (i in firstIdx until entries.size) {
            val page = entries[i].geometry
            val intersection = page.sceneBounds.intersectionOrNull(viewport.bounds)
            if (intersection != null) {
                visibleNodes.add(
                    VisibleNode(
                        pageId = page.pageId,
                        sceneBounds = page.sceneBounds,
                        visibleRegion = intersection,
                        zIndex = page.zIndex,
                    ),
                )
            } else if (page.sceneBounds.top >= viewport.bounds.bottom) {
                break
            }
        }
        return ReaderFrame(viewport, visibleNodes, readingDirection)
    }

    /**
     * Resolves the active reading page ID for a given [viewport].
     *
     * In vertical webtoon reading, the reading anchor is the first visible page spanning
     * the top of the viewport (`nodes.first().pageId`), which guarantees that intra-page
     * scroll offsets are strictly positive, invertible, and prevent progress drift across sessions.
     */
    override fun resolveActivePageId(viewport: ReaderViewport): PageId? {
        return resolve(viewport).progress.activePageId
    }

    /**
     * Updates the geometry hint for a specific page (e.g. from [PageGeometryHint.Estimated] to
     * [PageGeometryHint.Exact]).
     *
     * If [currentViewport] is provided, computes an [AnchorCompensation] preserving the visual reading position
     * without any perceptual jump (Zero CLS with Anchored Correction).
     */
    override fun updatePageHint(
        pageId: PageId,
        newHint: PageGeometryHint,
        currentViewport: ReaderViewport?,
    ): AnchorCompensation? {
        val index = indexOf(pageId)
        if (index < 0) return null

        val entry = entries[index]
        val oldBounds = entry.geometry.sceneBounds
        val oldHeight = oldBounds.height
        val oldTop = oldBounds.top
        val oldBottom = oldBounds.bottom
        val newHeight = computePageHeight(newHint)
        val deltaHeight = newHeight - oldHeight

        entry.hint = newHint

        if (deltaHeight == 0f) {
            return currentViewport?.let { AnchorCompensation(0f, it) }
        }

        // Relayout the changed page and all subsequent pages
        var currentY = oldTop
        for (i in index until entries.size) {
            val currentEntry = entries[i]
            val height = if (i == index) newHeight else currentEntry.geometry.sceneBounds.height
            currentEntry.geometry = PageGeometry(
                pageId = currentEntry.pageId,
                sceneBounds = FloatRect.fromLtwh(0f, currentY, availableWidth.toFloat(), height),
                zIndex = currentEntry.geometry.zIndex,
            )
            currentY += height
            if (i < entries.size - 1) {
                currentY += pageSpacingPx.toFloat()
            }
        }
        totalSceneHeight = currentY
        revision++

        if (currentViewport == null) return null

        val viewportTop = currentViewport.bounds.top
        val viewportBottom = currentViewport.bounds.bottom

        val deltaY: Float = when {
            // Case 1: Page was completely below viewport -> no scroll compensation needed
            oldTop >= viewportBottom -> 0f

            // Case 2: Page was completely above viewport -> shift viewport down by deltaHeight
            oldBottom <= viewportTop -> deltaHeight

            // Case 3: Viewport was currently intersecting this page -> preserve intra-page pixel offset
            else -> {
                val intraPageOffset = viewportTop - oldTop
                if (intraPageOffset > 0f) {
                    if (intraPageOffset > newHeight) {
                        newHeight - intraPageOffset
                    } else {
                        0f
                    }
                } else {
                    0f
                }
            }
        }

        val newBounds = currentViewport.bounds.translate(0f, deltaY)
        return AnchorCompensation(
            deltaY = deltaY,
            compensatedViewport = currentViewport.copy(bounds = newBounds),
        )
    }

    private fun computePageHeight(hint: PageGeometryHint): Float {
        return when (hint) {
            is PageGeometryHint.Exact -> {
                if (availableWidth <= 0 || hint.width <= 0 || hint.height <= 0) {
                    defaultViewportHeight.toFloat()
                } else {
                    (hint.height.toFloat() * availableWidth / hint.width).toInt().coerceAtLeast(1).toFloat()
                }
            }
            is PageGeometryHint.AspectRatio -> {
                if (availableWidth <= 0 || hint.ratio <= 0f) {
                    defaultViewportHeight.toFloat()
                } else {
                    (availableWidth.toFloat() / hint.ratio).toInt().coerceAtLeast(1).toFloat()
                }
            }
            is PageGeometryHint.Estimated -> {
                defaultViewportHeight.toFloat()
            }
        }
    }

    private fun findFirstVisibleIndex(viewportTop: Float): Int {
        var low = 0
        var high = entries.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val page = entries[mid].geometry
            if (page.sceneBounds.bottom > viewportTop) {
                result = mid
                high = mid - 1 // Try to find an earlier page that is also visible
            } else {
                low = mid + 1
            }
        }
        return result
    }
}
