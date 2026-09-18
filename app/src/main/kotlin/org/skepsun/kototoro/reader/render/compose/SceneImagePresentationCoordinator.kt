package org.skepsun.kototoro.reader.render.compose

import kotlin.math.ceil
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderCameraSnapshot
import org.skepsun.kototoro.reader.core.ReaderFrame
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset

/**
 * Unified presentation coordinator responsible for translating [ReaderFrame] and camera viewport
 * state into tile requests and resolution-aware LOD triggers.
 *
 * Implements ADR 0002 Phase 3E:
 * - Solves the critical presentation gap where paged readers only rendered LOD0 overviews.
 * - Extracts shared tile coordinate math and camera settle debouncing from Webtoon and Horizontal readers.
 */
object SceneImagePresentationCoordinator {

    /** Diagnostic counter for oversized node geometry; see the probe in [coordinateVisibleTiles]. */
    private val tileGeometryProbes = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Inspects visible nodes in [frame] and requests intersecting lattice tiles for any [ReaderImageAsset.Tiled] asset.
     */
    fun coordinateVisibleTiles(
        frame: ReaderFrame,
        retainedAssets: Map<PageId, ReaderImageAsset>,
        pipeline: KototoroImagePipelineAdapter,
    ) {
        for (node in frame.visibleNodes) {
            val asset = retainedAssets[node.pageId]
            if (asset is ReaderImageAsset.Tiled && node.sceneBounds.width > 0f && node.sceneBounds.height > 0f) {
                val visibleLogical = computeVisibleLogicalRect(
                    nodeSceneBounds = node.sceneBounds,
                    nodeVisibleRegion = node.visibleRegion,
                    imageWidth = asset.grid.pageSize.width,
                    imageHeight = asset.grid.pageSize.height,
                )
                // Diagnostic: the node geometry behind an oversized request, so the caller that
                // produces it is identified from values rather than inferred.
                if (visibleLogical.width.toLong() * visibleLogical.height > 6_000_000L) {
                    val probe = tileGeometryProbes.incrementAndGet()
                    if (probe <= 8) {
                        android.util.Log.w(
                            "TileGeom",
                            "oversized node probe=$probe page=${node.pageId.value} " +
                                "sceneBounds=${node.sceneBounds} visibleRegion=${node.visibleRegion} " +
                                "gridPageSize=${asset.grid.pageSize} sampleSize=${asset.grid.sampleSize} " +
                                "logical=$visibleLogical",
                        )
                    }
                }
                pipeline.requestTiles(node.pageId, visibleLogical)
            }
        }
    }

    /**
     * Maps an intersection between a page's scene bounds and the visible viewport into logical image pixel coordinates.
     */
    fun computeVisibleLogicalRect(
        nodeSceneBounds: FloatRect,
        nodeVisibleRegion: FloatRect,
        imageWidth: Int,
        imageHeight: Int,
    ): IntRect {
        val scaleX = imageWidth.toFloat() / nodeSceneBounds.width
        val scaleY = imageHeight.toFloat() / nodeSceneBounds.height

        val visRelLeft = (nodeVisibleRegion.left - nodeSceneBounds.left) * scaleX
        val visRelTop = (nodeVisibleRegion.top - nodeSceneBounds.top) * scaleY
        val visRelRight = (nodeVisibleRegion.right - nodeSceneBounds.left) * scaleX
        val visRelBottom = (nodeVisibleRegion.bottom - nodeSceneBounds.top) * scaleY

        val left = visRelLeft.toInt().coerceIn(0, imageWidth)
        val top = visRelTop.toInt().coerceIn(0, imageHeight)
        val right = ceil(visRelRight).toInt().coerceIn(left, imageWidth)
        val bottom = ceil(visRelBottom).toInt().coerceIn(top, imageHeight)

        return IntRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )
    }

    /**
     * Calculates the scene-space visible bounds under canvas scale and translation offset.
     */
    fun computeVisibleBounds(
        viewportWidth: Float,
        viewportHeight: Float,
        scrollOffset: Float,
        isHorizontal: Boolean,
        canvasScale: Float,
        canvasOffsetX: Float,
        canvasOffsetY: Float,
        totalSceneExtent: Float,
        totalCrossExtent: Float,
        effectiveViewportHeight: Float = viewportHeight,
    ): FloatRect {
        val centerX = viewportWidth / 2f
        val centerY = effectiveViewportHeight / 2f

        val leftRel = (centerX + (0f - canvasOffsetX - centerX) / canvasScale).coerceAtLeast(0f)
        val rightRel = (centerX + (viewportWidth - canvasOffsetX - centerX) / canvasScale).coerceAtLeast(0f)
        val topRel = (centerY + (0f - canvasOffsetY - centerY) / canvasScale).coerceAtLeast(0f)
        val bottomRel = (centerY + (viewportHeight - canvasOffsetY - centerY) / canvasScale).coerceAtLeast(0f)

        return if (isHorizontal) {
            val left = (scrollOffset + leftRel).coerceIn(0f, totalSceneExtent)
            val right = (scrollOffset + rightRel).coerceIn(0f, totalSceneExtent)
            val top = topRel.coerceIn(0f, totalCrossExtent)
            val bottom = bottomRel.coerceIn(0f, totalCrossExtent)
            FloatRect(left = left, top = top, right = right, bottom = bottom)
        } else {
            val left = leftRel.coerceIn(0f, totalCrossExtent)
            val right = rightRel.coerceIn(0f, totalCrossExtent)
            val top = (scrollOffset + topRel).coerceIn(0f, totalSceneExtent)
            val bottom = (scrollOffset + bottomRel).coerceIn(0f, totalSceneExtent)
            FloatRect(left = left, top = top, right = right, bottom = bottom)
        }
    }

    /**
     * Creates a camera snapshot from scale and visible scene bounds.
     */
    fun createCameraSnapshot(
        scale: Float,
        visibleBoundsInScene: FloatRect,
    ): ReaderCameraSnapshot = ReaderCameraSnapshot(
        scale = scale,
        visibleBoundsInScene = visibleBoundsInScene,
    )
}
