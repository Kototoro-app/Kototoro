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
) {
    init {
        require(availableWidth > 0) { "availableWidth must be > 0: $availableWidth" }
        require(defaultViewportHeight > 0) { "defaultViewportHeight must be > 0: $defaultViewportHeight" }
    }

    private val entries = ArrayList<PageEntry>(initialPages.size)

    /** Ordered list of laid-out page geometries. */
    val pageGeometries: List<PageGeometry> get() = entries.map { it.geometry }

    /** Total height of the continuous scene in pixels. */
    var totalSceneHeight: Float = 0f
        private set

    val pageCount: Int get() = entries.size

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
    fun setPages(pages: List<Pair<PageId, PageGeometryHint>>) {
        entries.clear()
        entries.ensureCapacity(pages.size)
        var currentY = 0f
        for ((pageId, hint) in pages) {
            val pageHeight = computePageHeight(hint)
            val bounds = FloatRect.fromLtwh(0f, currentY, availableWidth.toFloat(), pageHeight)
            val geometry = PageGeometry(pageId, bounds)
            entries.add(PageEntry(pageId, hint, geometry))
            currentY += pageHeight
        }
        totalSceneHeight = currentY
    }

    /**
     * Returns the 0-indexed position of [pageId] in the scene, or -1 if absent.
     */
    fun indexOf(pageId: PageId): Int {
        for (i in entries.indices) {
            if (entries[i].pageId == pageId) return i
        }
        return -1
    }

    /**
     * Resolves the top Y coordinate for [pageId] in scene coordinates, or null if absent.
     */
    fun resolvePageScrollPosition(pageId: PageId): Float? {
        val index = indexOf(pageId)
        if (index < 0) return null
        return entries[index].geometry.sceneBounds.top
    }

    /**
     * Computes the visible nodes and active reading semantic state for a given [viewport].
     */
    fun resolve(viewport: ReaderViewport): ReaderFrame {
        if (entries.isEmpty() || viewport.bounds.isEmpty) {
            return ReaderFrame(viewport, emptyList())
        }
        val firstIdx = findFirstVisibleIndex(viewport.bounds.top)
        if (firstIdx < 0) {
            return ReaderFrame(viewport, emptyList())
        }

        val visibleNodes = ArrayList<VisibleNode>(8)
        for (i in firstIdx until entries.size) {
            val page = entries[i].geometry
            val intersection = page.sceneBounds.intersectionOrNull(viewport.bounds) ?: break
            visibleNodes.add(
                VisibleNode(
                    pageId = page.pageId,
                    sceneBounds = page.sceneBounds,
                    visibleRegion = intersection,
                    zIndex = page.zIndex,
                ),
            )
        }
        return ReaderFrame(viewport, visibleNodes)
    }

    /**
     * Resolves the active reading page ID for a given [viewport].
     *
     * In vertical webtoon reading, the reading anchor is the first visible page spanning
     * the top of the viewport (`nodes.first().pageId`), which guarantees that intra-page
     * scroll offsets are strictly positive, invertible, and prevent progress drift across sessions.
     */
    fun resolveActivePageId(viewport: ReaderViewport): PageId? {
        val frame = resolve(viewport)
        val nodes = frame.visibleNodes
        if (nodes.isEmpty()) return null
        return nodes.first().pageId
    }

    /**
     * Result of an anchored layout compensation when page dimensions change.
     *
     * @property deltaY The change in scroll offset required to keep the active view visually fixed.
     * @property compensatedViewport The adjusted viewport matching the new scene layout.
     */
    data class AnchorCompensation(
        val deltaY: Float,
        val compensatedViewport: ReaderViewport,
    )

    /**
     * Updates the geometry hint for a specific page (e.g. from [PageGeometryHint.Estimated] to
     * [PageGeometryHint.Exact]).
     *
     * If [currentViewport] is provided, computes an [AnchorCompensation] preserving the visual reading position
     * without any perceptual jump (Zero CLS with Anchored Correction).
     */
    fun updatePageHint(
        pageId: PageId,
        newHint: PageGeometryHint,
        currentViewport: ReaderViewport? = null,
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
        }
        totalSceneHeight = currentY

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
