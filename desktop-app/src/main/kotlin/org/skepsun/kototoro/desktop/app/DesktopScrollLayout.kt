package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.reader.core.*

/**
 * The geometry of one continuous strip, whichever way it runs: the virtualized list draws an item per page with the
 * scene's extent, and the viewport that resolves progress is the scene rectangle at the list's scroll position.
 */
internal interface DesktopStripLayout {
    val scene: ReaderScene

    /** The page's size along the strip axis: its height for the vertical strip, its width for the horizontal one. */
    fun extent(index: Int): Float

    /** The viewport when the strip shows [first] scrolled [offset] pixels past that page's leading edge. */
    fun frame(first: Int, offset: Int): ReaderFrame
}

/** Lazy items use the same scene geometry that resolves viewport progress, including unknown page headers. */
internal class DesktopScrollLayout(pages: List<SourcePage>, images: Map<Long, DesktopReaderImage>,
    width: Int, height: Int, spacing: Int, geometry: Map<Long, IntSize> = emptyMap()) : DesktopStripLayout {
    override val scene = VerticalReaderScene(width.coerceAtLeast(1), height.coerceAtLeast(1), pages.map { page ->
        PageId(page.id) to pageHint(page, images, geometry)
    }, spacing)
    val geometries = scene.pageGeometries

    override fun extent(index: Int): Float = geometries.getOrNull(index)?.sceneBounds?.height ?: 0f

    override fun frame(first: Int, offset: Int): ReaderFrame {
        val top = geometries.getOrNull(first)?.sceneBounds?.top ?: 0f
        return scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, top + offset.coerceAtLeast(0),
            scene.availableWidth.toFloat(), scene.defaultViewportHeight.toFloat())))
    }
}

/**
 * Android's continuous horizontal reader: the pages are fitted to the window height and laid out in one row, read left
 * to right — or right to left when [reversed] asks for it, which is the same thing [HorizontalReaderScene] does with its
 * reading direction. The list scrolls in reading order either way, so forward is always the next page.
 */
internal class DesktopHorizontalScrollLayout(pages: List<SourcePage>, images: Map<Long, DesktopReaderImage>,
    width: Int, height: Int, spacing: Int, reversed: Boolean, geometry: Map<Long, IntSize> = emptyMap()) : DesktopStripLayout {
    private val pageIds = pages.map { PageId(it.id) }
    override val scene = HorizontalReaderScene(
        availableHeight = height.coerceAtLeast(1),
        defaultViewportWidth = width.coerceAtLeast(1),
        initialPages = pages.map { page -> PageId(page.id) to pageHint(page, images, geometry) },
        pageSpacingPx = spacing,
        readingDirection = if (reversed) SceneReadingDirection.RIGHT_TO_LEFT else SceneReadingDirection.LEFT_TO_RIGHT,
    )

    override fun extent(index: Int): Float = scene.pageGeometries.getOrNull(index)?.sceneBounds?.width ?: 0f

    override fun frame(first: Int, offset: Int): ReaderFrame {
        val leading = pageIds.getOrNull(first)?.let(scene::resolvePageScrollPosition) ?: 0f
        val viewportWidth = scene.defaultViewportWidth.toFloat()
        val viewportHeight = scene.availableHeight.toFloat()
        val scrolled = offset.coerceAtLeast(0).toFloat()
        val left = if (scene.readingDirection == SceneReadingDirection.RIGHT_TO_LEFT) {
            leading - scrolled - viewportWidth
        } else {
            leading + scrolled
        }
        return scene.resolve(ReaderViewport(FloatRect.fromLtwh(left, 0f, viewportWidth, viewportHeight)))
    }
}

/**
 * A page's size for the strip: the loaded image first, then the size the image index remembers, and an estimate for a
 * page that has never been seen — the same order Android's scenes are given.
 */
private fun pageHint(page: SourcePage, images: Map<Long, DesktopReaderImage>,
    geometry: Map<Long, IntSize>): PageGeometryHint =
    images[page.id]?.let { PageGeometryHint.Exact(it.displayWidth, it.displayHeight) }
        ?: geometry[page.id]?.let { PageGeometryHint.Exact(it.width, it.height) }
        ?: PageGeometryHint.Estimated(.7f)
