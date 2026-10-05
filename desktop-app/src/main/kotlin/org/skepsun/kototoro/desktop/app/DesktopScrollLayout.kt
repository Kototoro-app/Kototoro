package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.reader.core.*

/** Lazy items use the same scene geometry that resolves viewport progress, including unknown page headers. */
internal class DesktopScrollLayout(pages: List<SourcePage>, images: Map<Long, DesktopReaderImage>,
    width: Int, height: Int, spacing: Int, geometry: Map<Long, IntSize> = emptyMap()) {
    val scene = VerticalReaderScene(width.coerceAtLeast(1), height.coerceAtLeast(1), pages.map { page ->
        PageId(page.id) to (images[page.id]?.let { PageGeometryHint.Exact(it.displayWidth, it.displayHeight) }
            ?: geometry[page.id]?.let { PageGeometryHint.Exact(it.width, it.height) }
            ?: PageGeometryHint.Estimated(.7f))
    }, spacing)
    val geometries = scene.pageGeometries

    fun frame(first: Int, offset: Int): ReaderFrame {
        val top = geometries.getOrNull(first)?.sceneBounds?.top ?: 0f
        return scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, top + offset.coerceAtLeast(0),
            scene.availableWidth.toFloat(), scene.defaultViewportHeight.toFloat())))
    }
}
