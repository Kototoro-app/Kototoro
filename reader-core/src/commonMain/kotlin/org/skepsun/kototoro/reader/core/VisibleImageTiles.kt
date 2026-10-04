package org.skepsun.kototoro.reader.core

import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.VisibleNode
import kotlin.math.ceil
import kotlin.math.floor

/** Scene intersections select the existing source-space grid; no platform decoder or pixels live here. */
object VisibleImageTiles {
    fun resolve(node: VisibleNode, encodedSize: IntSize, displayScale: Float = 1f): List<TileSpec> {
        require(displayScale.isFinite() && displayScale > 0f)
        if (node.sceneBounds.isEmpty || encodedSize.isEmpty) return emptyList()
        val visible = node.visibleRegion.intersectionOrNull(node.sceneBounds) ?: return emptyList()
        val x = encodedSize.width.toDouble() / node.sceneBounds.width
        val y = encodedSize.height.toDouble() / node.sceneBounds.height
        val region = IntRect(
            floor((visible.left - node.sceneBounds.left) * x).toInt().coerceIn(0, encodedSize.width),
            floor((visible.top - node.sceneBounds.top) * y).toInt().coerceIn(0, encodedSize.height),
            ceil((visible.right - node.sceneBounds.left) * x).toInt().coerceIn(0, encodedSize.width),
            ceil((visible.bottom - node.sceneBounds.top) * y).toInt().coerceIn(0, encodedSize.height),
        )
        var sample = 1
        val ratio = x / displayScale
        while (sample < 1 shl 20 && sample * 2 <= ratio) sample *= 2
        return TileGrid(node.pageId, ImageSourceGeometry(encodedSize),
            tileDimension = IntSize(512 * sample, 512 * sample), sampleSize = sample,
            outputGutterPx = 0).tilesIntersecting(region)
    }
}
