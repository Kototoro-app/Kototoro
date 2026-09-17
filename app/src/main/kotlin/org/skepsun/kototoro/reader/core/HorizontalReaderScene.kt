package org.skepsun.kototoro.reader.core

/**
 * Continuous horizontal 2D scene for horizontal continuous / webtoon reading (ADR 0002 Phase 2C).
 *
 * Encapsulates layout rules, binary search viewport resolution, and anchored correction
 * for both [SceneReadingDirection.LEFT_TO_RIGHT] and [SceneReadingDirection.RIGHT_TO_LEFT].
 *
 * Implements ADR 0002 Constraint 1:
 * - Decouples scene layout from image decoding status.
 * - Pure Kotlin with zero Android / Compose dependencies.
 * - Provides [updatePageHint] with [AnchorCompensation] to ensure zero perceptual jump when
 *   estimated dimensions are updated with exact dimensions (Zero CLS).
 */
class HorizontalReaderScene(
    val availableHeight: Int,
    val defaultViewportWidth: Int,
    initialPages: List<Pair<PageId, PageGeometryHint>> = emptyList(),
    val pageSpacingPx: Int = 0,
    override val readingDirection: SceneReadingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
) : MutableReaderScene {
    init {
        require(availableHeight > 0) { "availableHeight must be > 0: $availableHeight" }
        require(defaultViewportWidth > 0) { "defaultViewportWidth must be > 0: $defaultViewportWidth" }
        require(pageSpacingPx >= 0) { "pageSpacingPx must be >= 0: $pageSpacingPx" }
        require(readingDirection.isHorizontal) {
            "HorizontalReaderScene requires a horizontal reading direction (LEFT_TO_RIGHT or RIGHT_TO_LEFT), got $readingDirection"
        }
    }

    private val entries = ArrayList<PageEntry>(initialPages.size)

    /** Ordered list of laid-out page geometries in canonical reading order. */
    override val pageGeometries: List<PageGeometry> get() = entries.map { it.geometry }

    /** Total width of the continuous scene in pixels. */
    var totalSceneWidth: Float = 0f
        private set

    override val totalSceneExtent: Float get() = totalSceneWidth

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
        if (pages.isEmpty()) {
            totalSceneWidth = 0f
            revision++
            return
        }

        val widths = FloatArray(pages.size) { i -> computePageWidth(pages[i].second) }
        var sumWidths = 0f
        for (w in widths) {
            sumWidths += w
        }
        val totalWidth = sumWidths + (pages.size - 1) * pageSpacingPx.toFloat()

        if (readingDirection == SceneReadingDirection.LEFT_TO_RIGHT) {
            var currentX = 0f
            for (i in pages.indices) {
                val (pageId, hint) = pages[i]
                val w = widths[i]
                val bounds = FloatRect.fromLtwh(currentX, 0f, w, availableHeight.toFloat())
                val geometry = PageGeometry(pageId, bounds)
                entries.add(PageEntry(pageId, hint, geometry))
                currentX += w
                if (i < pages.size - 1) {
                    currentX += pageSpacingPx.toFloat()
                }
            }
            totalSceneWidth = currentX
        } else { // RIGHT_TO_LEFT
            var currentRight = totalWidth
            for (i in pages.indices) {
                val (pageId, hint) = pages[i]
                val w = widths[i]
                val bounds = FloatRect.fromLtwh(currentRight - w, 0f, w, availableHeight.toFloat())
                val geometry = PageGeometry(pageId, bounds)
                entries.add(PageEntry(pageId, hint, geometry))
                currentRight -= w
                if (i < pages.size - 1) {
                    currentRight -= pageSpacingPx.toFloat()
                }
            }
            totalSceneWidth = totalWidth
        }
        revision++
    }

    /**
     * Updates the page list in the continuous scene, preserving exact geometry hints for pages
     * that already have them, and computing an [AnchorCompensation] to prevent perceptual jumps
     * when pages are prepended or appended (e.g. cross-chapter window expansions).
     */
    override fun updatePages(
        newPages: List<Pair<PageId, PageGeometryHint>>,
        currentViewport: ReaderViewport?,
    ): AnchorCompensation? {
        if (newPages.isEmpty()) {
            entries.clear()
            totalSceneWidth = 0f
            revision++
            return null
        }

        val existingHints = entries.associate { it.pageId to it.hint }
        val activeAnchorId = currentViewport?.let { resolveActivePageId(it) }
        val oldAnchorPos = activeAnchorId?.let { resolvePageScrollPosition(it) }
        val oldIntraPageOffset = if (currentViewport != null && oldAnchorPos != null) {
            when (readingDirection) {
                SceneReadingDirection.LEFT_TO_RIGHT -> currentViewport.bounds.left - oldAnchorPos
                SceneReadingDirection.RIGHT_TO_LEFT -> oldAnchorPos - currentViewport.bounds.right
                SceneReadingDirection.TOP_TO_BOTTOM -> currentViewport.bounds.top - oldAnchorPos
            }
        } else null

        val resolvedPages = newPages.map { (pageId, hint) ->
            val existing = existingHints[pageId]
            val effectiveHint = if (existing is PageGeometryHint.Exact) existing else hint
            pageId to effectiveHint
        }

        setPages(resolvedPages)

        if (currentViewport == null || activeAnchorId == null || oldAnchorPos == null || oldIntraPageOffset == null) {
            return null
        }

        val newAnchorPos = resolvePageScrollPosition(activeAnchorId) ?: return null
        val deltaX: Float = when (readingDirection) {
            SceneReadingDirection.LEFT_TO_RIGHT -> {
                val targetViewportLeft = newAnchorPos + oldIntraPageOffset
                targetViewportLeft - currentViewport.bounds.left
            }
            SceneReadingDirection.RIGHT_TO_LEFT -> {
                val targetViewportRight = newAnchorPos - oldIntraPageOffset
                targetViewportRight - currentViewport.bounds.right
            }
            SceneReadingDirection.TOP_TO_BOTTOM -> 0f
        }

        val newBounds = currentViewport.bounds.translate(deltaX, 0f)
        return AnchorCompensation(
            deltaX = deltaX,
            deltaY = 0f,
            compensatedViewport = currentViewport.copy(bounds = newBounds),
        )
    }

    /**
     * Returns the 0-indexed position of [pageId] in canonical reading order, or -1 if absent.
     */
    override fun indexOf(pageId: PageId): Int {
        for (i in entries.indices) {
            if (entries[i].pageId == pageId) return i
        }
        return -1
    }

    /**
     * Resolves the coordinate of [pageId] along the primary horizontal reading axis in scene coordinates:
     * - [SceneReadingDirection.LEFT_TO_RIGHT]: left coordinate of page
     * - [SceneReadingDirection.RIGHT_TO_LEFT]: right coordinate of page
     */
    override fun resolvePageScrollPosition(pageId: PageId): Float? {
        val index = indexOf(pageId)
        if (index < 0) return null
        return when (readingDirection) {
            SceneReadingDirection.LEFT_TO_RIGHT -> entries[index].geometry.sceneBounds.left
            SceneReadingDirection.RIGHT_TO_LEFT -> entries[index].geometry.sceneBounds.right
            SceneReadingDirection.TOP_TO_BOTTOM -> entries[index].geometry.sceneBounds.top
        }
    }

    /**
     * Computes the visible nodes and active reading semantic state for a given [viewport].
     *
     * Nodes in [ReaderFrame.visibleNodes] are guaranteed to be in canonical reading order.
     */
    override fun resolve(viewport: ReaderViewport): ReaderFrame {
        if (entries.isEmpty() || viewport.bounds.isEmpty) {
            return ReaderFrame(viewport, emptyList(), readingDirection)
        }
        val firstIdx = findFirstVisibleIndex(viewport)
        if (firstIdx < 0) {
            return ReaderFrame(viewport, emptyList(), readingDirection)
        }

        val visibleNodes = ArrayList<VisibleNode>(8)
        if (readingDirection == SceneReadingDirection.LEFT_TO_RIGHT) {
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
                } else if (page.sceneBounds.left >= viewport.bounds.right) {
                    break
                }
            }
        } else { // RIGHT_TO_LEFT
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
                } else if (page.sceneBounds.right <= viewport.bounds.left) {
                    break
                }
            }
        }
        return ReaderFrame(viewport, visibleNodes, readingDirection)
    }

    /**
     * Resolves the active reading page ID for a given [viewport].
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
        val oldWidth = oldBounds.width
        val newWidth = computePageWidth(newHint)
        val deltaWidth = newWidth - oldWidth

        entry.hint = newHint

        if (deltaWidth == 0f) {
            return currentViewport?.let { AnchorCompensation(deltaX = 0f, deltaY = 0f, compensatedViewport = it) }
        }

        if (readingDirection == SceneReadingDirection.LEFT_TO_RIGHT) {
            val oldLeft = oldBounds.left
            val oldRight = oldBounds.right
            var currentX = oldLeft
            for (i in index until entries.size) {
                val currentEntry = entries[i]
                val width = if (i == index) newWidth else currentEntry.geometry.sceneBounds.width
                currentEntry.geometry = PageGeometry(
                    pageId = currentEntry.pageId,
                    sceneBounds = FloatRect.fromLtwh(currentX, 0f, width, availableHeight.toFloat()),
                    zIndex = currentEntry.geometry.zIndex,
                )
                currentX += width
                if (i < entries.size - 1) {
                    currentX += pageSpacingPx.toFloat()
                }
            }
            totalSceneWidth = currentX
            revision++

            if (currentViewport == null) return null

            val viewportLeft = currentViewport.bounds.left
            val viewportRight = currentViewport.bounds.right

            val deltaX: Float = when {
                oldLeft >= viewportRight -> 0f
                oldRight <= viewportLeft -> deltaWidth
                else -> {
                    val intraPageOffset = viewportLeft - oldLeft
                    if (intraPageOffset > 0f) {
                        if (intraPageOffset > newWidth) {
                            newWidth - intraPageOffset
                        } else {
                            0f
                        }
                    } else {
                        0f
                    }
                }
            }
            val newBounds = currentViewport.bounds.translate(deltaX, 0f)
            return AnchorCompensation(
                deltaX = deltaX,
                deltaY = 0f,
                compensatedViewport = currentViewport.copy(bounds = newBounds),
            )
        } else { // RIGHT_TO_LEFT
            val oldLeft = oldBounds.left
            val oldRight = oldBounds.right

            // In RTL with origin at 0, pages k < index (to the right) shift by +deltaWidth.
            // Page index left edge stays at oldLeft, right edge becomes oldRight + deltaWidth.
            // Pages k > index (to the left) are unchanged.
            for (i in 0 until index) {
                val currentEntry = entries[i]
                val b = currentEntry.geometry.sceneBounds
                currentEntry.geometry = PageGeometry(
                    pageId = currentEntry.pageId,
                    sceneBounds = FloatRect.fromLtwh(b.left + deltaWidth, 0f, b.width, availableHeight.toFloat()),
                    zIndex = currentEntry.geometry.zIndex,
                )
            }
            entry.geometry = PageGeometry(
                pageId = entry.pageId,
                sceneBounds = FloatRect.fromLtwh(oldLeft, 0f, newWidth, availableHeight.toFloat()),
                zIndex = entry.geometry.zIndex,
            )
            totalSceneWidth += deltaWidth
            revision++

            if (currentViewport == null) return null

            val viewportLeft = currentViewport.bounds.left
            val viewportRight = currentViewport.bounds.right

            val deltaX: Float = when {
                oldLeft >= viewportRight -> 0f
                oldRight <= viewportLeft -> deltaWidth
                else -> {
                    val intraPageOffset = oldRight - viewportRight
                    if (intraPageOffset > 0f) {
                        if (intraPageOffset > newWidth) {
                            oldLeft - viewportRight
                        } else {
                            deltaWidth
                        }
                    } else {
                        deltaWidth
                    }
                }
            }
            val newBounds = currentViewport.bounds.translate(deltaX, 0f)
            return AnchorCompensation(
                deltaX = deltaX,
                deltaY = 0f,
                compensatedViewport = currentViewport.copy(bounds = newBounds),
            )
        }
    }

    private fun computePageWidth(hint: PageGeometryHint): Float {
        return when (hint) {
            is PageGeometryHint.Exact -> {
                if (availableHeight <= 0 || hint.width <= 0 || hint.height <= 0) {
                    defaultViewportWidth.toFloat()
                } else {
                    (hint.width.toFloat() * availableHeight / hint.height).toInt().coerceAtLeast(1).toFloat()
                }
            }
            is PageGeometryHint.AspectRatio -> {
                if (availableHeight <= 0 || hint.ratio <= 0f) {
                    defaultViewportWidth.toFloat()
                } else {
                    (availableHeight.toFloat() * hint.ratio).toInt().coerceAtLeast(1).toFloat()
                }
            }
            is PageGeometryHint.Estimated -> {
                defaultViewportWidth.toFloat()
            }
        }
    }

    private fun findFirstVisibleIndex(viewport: ReaderViewport): Int {
        return if (readingDirection == SceneReadingDirection.LEFT_TO_RIGHT) {
            findFirstVisibleIndexLtr(viewport.bounds.left)
        } else {
            findFirstVisibleIndexRtl(viewport.bounds.right)
        }
    }

    private fun findFirstVisibleIndexLtr(viewportLeft: Float): Int {
        var low = 0
        var high = entries.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val page = entries[mid].geometry
            if (page.sceneBounds.right > viewportLeft) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun findFirstVisibleIndexRtl(viewportRight: Float): Int {
        var low = 0
        var high = entries.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val page = entries[mid].geometry
            if (page.sceneBounds.left < viewportRight) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }
}
