package org.skepsun.kototoro.reader.render.compose

/**
 * Centers, along the scroll axis and in screen coordinates, of the loading indicators drawn over a
 * page that has nothing to draw yet.
 *
 * The placeholder itself is a uniform surface, so its indicators are what shows the page moving
 * under the finger. They are fixed to the page, never to its visible slice: an indicator centered
 * in the visible slice drifts at half the scroll speed while the page enters, and stands still once
 * the page covers the viewport, which makes scrolling across a loading page feel stuck. A page
 * taller than the viewport repeats its indicator every viewport extent, so any viewport-sized
 * window over it holds one.
 */
internal fun resolvePlaceholderIndicatorCenters(
    pageStart: Float,
    pageExtent: Float,
    viewportExtent: Float,
): List<Float> {
    if (pageExtent <= 0f) return emptyList()
    if (viewportExtent <= 0f || pageExtent <= viewportExtent) return listOf(pageStart + pageExtent / 2f)
    val centers = ArrayList<Float>((pageExtent / viewportExtent).toInt() + 1)
    var offset = viewportExtent / 2f
    while (offset < pageExtent) {
        centers.add(pageStart + offset)
        offset += viewportExtent
    }
    return centers
}
