package org.skepsun.kototoro.reader.core

/**
 * Geometric resolver computing the intersection between a [ReaderViewport] and a list of [PageGeometry].
 *
 * Implements linear scan for Phase 1, with in-place buffer reuse via [resolveInto] to avoid
 * heap churn during high-frequency scrolling.
 */
object VisibleRegionResolver {

    fun resolve(
        viewport: ReaderViewport,
        pages: List<PageGeometry>,
    ): ReaderFrame {
        if (viewport.bounds.isEmpty || pages.isEmpty()) {
            return ReaderFrame(viewport, emptyList())
        }
        val destination = ArrayList<VisibleNode>(minOf(pages.size, 8))
        resolveInto(viewport, pages, destination)
        return ReaderFrame(viewport, destination)
    }

    fun resolveInto(
        viewport: ReaderViewport,
        pages: List<PageGeometry>,
        destination: MutableList<VisibleNode>,
    ) {
        destination.clear()
        if (viewport.bounds.isEmpty || pages.isEmpty()) {
            return
        }
        val viewportBounds = viewport.bounds
        for (i in pages.indices) {
            val page = pages[i]
            val intersection = page.sceneBounds.intersectionOrNull(viewportBounds) ?: continue
            destination.add(
                VisibleNode(
                    pageId = page.pageId,
                    sceneBounds = page.sceneBounds,
                    visibleRegion = intersection,
                    zIndex = page.zIndex,
                ),
            )
        }
    }
}
