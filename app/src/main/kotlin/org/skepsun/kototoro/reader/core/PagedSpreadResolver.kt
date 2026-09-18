package org.skepsun.kototoro.reader.core

import kotlin.math.min
import org.skepsun.kototoro.core.model.ZoomMode

/**
 * Configuration governing page spread layout and pairing.
 */
data class PagedSpreadConfig(
    val isDoublePage: Boolean = false,
    val isCoverOffset: Boolean = false,
    val readingDirection: SceneReadingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
    val pageSpacingPx: Int = 0,
    val zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
)

/**
 * Resolves logical page specifications into discrete [PagedSlot]s.
 *
 * Implements Kototoro paged layout invariants:
 * - Chapter isolation: Adjacent pages belonging to different chapters are NEVER combined into the same slot.
 * - Solo pages: Wide pages (aspect ratio > 1.15) and cover offset pages occupy independent single-spread slots.
 * - Direction-aware placement: In RTL, earlier pages in reading order are placed on the right side of the spread.
 * - Deterministic progress anchoring: Each slot designates the primary reading progress [PageId].
 */
object PagedSpreadResolver {

    fun resolveSlots(
        specs: List<PagedPageSpec>,
        viewportWidth: Int,
        viewportHeight: Int,
        config: PagedSpreadConfig,
    ): List<PagedSlot> {
        if (specs.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) return emptyList()

        val vW = viewportWidth.toFloat()
        val vH = viewportHeight.toFloat()
        val isHorizontal = config.readingDirection.isHorizontal

        val slotGroups = groupSpecsIntoSlots(specs, config)
        val slots = ArrayList<PagedSlot>(slotGroups.size)

        var currentSceneX = 0f
        var currentSceneY = 0f

        for (slotIndex in slotGroups.indices) {
            val group = slotGroups[slotIndex]
            val slotBounds = if (isHorizontal) {
                FloatRect.fromLtwh(currentSceneX, 0f, vW, vH)
            } else {
                FloatRect.fromLtwh(0f, currentSceneY, vW, vH)
            }

            val placements = layoutGroupInSlot(
                group = group,
                slotBounds = slotBounds,
                viewportWidth = vW,
                viewportHeight = vH,
                config = config,
            )

            val anchorId = group.first().pageId
            slots.add(
                PagedSlot(
                    slotIndex = slotIndex,
                    bounds = slotBounds,
                    placements = placements,
                    progressAnchorPageId = anchorId,
                ),
            )

            if (isHorizontal) {
                currentSceneX += vW
            } else {
                currentSceneY += vH
            }
        }

        return slots
    }

    private fun groupSpecsIntoSlots(
        specs: List<PagedPageSpec>,
        config: PagedSpreadConfig,
    ): List<List<PagedPageSpec>> {
        if (!config.isDoublePage) {
            return specs.map { listOf(it) }
        }

        val result = mutableListOf<List<PagedPageSpec>>()
        var i = 0
        while (i < specs.size) {
            val first = specs[i]
            if (isSoloPage(first, config)) {
                result.add(listOf(first))
                i++
                continue
            }

            // Candidate pair
            if (i + 1 < specs.size) {
                val second = specs[i + 1]
                val canPair = second.chapterId == first.chapterId &&
                    !isSoloPage(second, config) &&
                    second.spreadBehavior != SpreadBehavior.SOLO

                if (canPair) {
                    result.add(listOf(first, second))
                    i += 2
                    continue
                }
            }

            // Odd or unpairable page at chapter end
            result.add(listOf(first))
            i++
        }
        return result
    }

    private fun isSoloPage(spec: PagedPageSpec, config: PagedSpreadConfig): Boolean {
        if (spec.spreadBehavior == SpreadBehavior.SOLO) return true
        if (config.isCoverOffset && spec.chapterPageIndex == 0) return true
        if (spec.segment == PageSegment.FULL && WidePagePolicy.isWidePage(spec.geometryHint)) {
            return true
        }
        return false
    }

    private fun layoutGroupInSlot(
        group: List<PagedPageSpec>,
        slotBounds: FloatRect,
        viewportWidth: Float,
        viewportHeight: Float,
        config: PagedSpreadConfig,
    ): List<PagedPagePlacement> {
        val zoomMode = config.zoomMode
        val isRtl = config.readingDirection == SceneReadingDirection.RIGHT_TO_LEFT

        if (group.size == 1) {
            val spec = group[0]
            val fitted = fitPage(spec.geometryHint, viewportWidth, viewportHeight, zoomMode)
            val leftInSlot = when (zoomMode) {
                ZoomMode.KEEP_START -> if (isRtl) (viewportWidth - fitted.width).coerceAtLeast(0f) else 0f
                else -> (viewportWidth - fitted.width) / 2f
            }
            val topInSlot = when (zoomMode) {
                ZoomMode.KEEP_START -> 0f
                else -> (viewportHeight - fitted.height) / 2f
            }
            val boundsInSlot = FloatRect.fromLtwh(leftInSlot, topInSlot, fitted.width, fitted.height)
            val sceneBounds = boundsInSlot.translate(slotBounds.left, slotBounds.top)
            return listOf(PagedPagePlacement(spec.pageId, boundsInSlot, sceneBounds))
        }

        // Two pages in double-page spread
        val firstSpec = group[0]
        val secondSpec = group[1]

        val halfWidth = (viewportWidth - config.pageSpacingPx.toFloat()).coerceAtLeast(0f) / 2f
        val fittedFirst = fitPage(firstSpec.geometryHint, halfWidth, viewportHeight, zoomMode)
        val fittedSecond = fitPage(secondSpec.geometryHint, halfWidth, viewportHeight, zoomMode)

        // In RTL: first page in reading order is on the right, second on the left
        val (leftSpec, leftFitted, rightSpec, rightFitted) = if (isRtl) {
            Quad(secondSpec, fittedSecond, firstSpec, fittedFirst)
        } else {
            Quad(firstSpec, fittedFirst, secondSpec, fittedSecond)
        }

        val leftSlotLeft = 0f
        val leftPageLeftInSlot = when (zoomMode) {
            ZoomMode.KEEP_START -> if (isRtl) leftSlotLeft + (halfWidth - leftFitted.width).coerceAtLeast(0f) else leftSlotLeft
            else -> leftSlotLeft + (halfWidth - leftFitted.width).coerceAtLeast(0f) / 2f
        }
        val leftPageTopInSlot = when (zoomMode) {
            ZoomMode.KEEP_START -> 0f
            else -> (viewportHeight - leftFitted.height).coerceAtLeast(0f) / 2f
        }
        val leftBoundsInSlot = FloatRect.fromLtwh(leftPageLeftInSlot, leftPageTopInSlot, leftFitted.width, leftFitted.height)
        val leftSceneBounds = leftBoundsInSlot.translate(slotBounds.left, slotBounds.top)

        val rightSlotLeft = halfWidth + config.pageSpacingPx.toFloat()
        val rightPageLeftInSlot = when (zoomMode) {
            ZoomMode.KEEP_START -> if (isRtl) rightSlotLeft + (halfWidth - rightFitted.width).coerceAtLeast(0f) else rightSlotLeft
            else -> rightSlotLeft + (halfWidth - rightFitted.width).coerceAtLeast(0f) / 2f
        }
        val rightPageTopInSlot = when (zoomMode) {
            ZoomMode.KEEP_START -> 0f
            else -> (viewportHeight - rightFitted.height).coerceAtLeast(0f) / 2f
        }
        val rightBoundsInSlot = FloatRect.fromLtwh(rightPageLeftInSlot, rightPageTopInSlot, rightFitted.width, rightFitted.height)
        val rightSceneBounds = rightBoundsInSlot.translate(slotBounds.left, slotBounds.top)

        val leftPlacement = PagedPagePlacement(leftSpec.pageId, leftBoundsInSlot, leftSceneBounds)
        val rightPlacement = PagedPagePlacement(rightSpec.pageId, rightBoundsInSlot, rightSceneBounds)

        return if (isRtl) {
            listOf(rightPlacement, leftPlacement) // Order by canonical reading order (firstSpec, then secondSpec)
        } else {
            listOf(leftPlacement, rightPlacement)
        }
    }

    private data class PageSize(val width: Float, val height: Float)

    private fun fitPage(
        hint: PageGeometryHint,
        containerW: Float,
        containerH: Float,
        zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
    ): PageSize {
        if (containerW <= 0f || containerH <= 0f) return PageSize(0f, 0f)
        val ratio = when (hint) {
            is PageGeometryHint.Exact -> if (hint.height > 0) hint.width.toFloat() / hint.height.toFloat() else 1f
            is PageGeometryHint.AspectRatio -> hint.ratio.coerceAtLeast(0.01f)
            is PageGeometryHint.Estimated -> hint.ratio.coerceAtLeast(0.01f)
        }

        return when (zoomMode) {
            ZoomMode.FIT_CENTER, ZoomMode.KEEP_START -> {
                val scale = min(containerW / ratio, containerH)
                PageSize(scale * ratio, scale)
            }
            ZoomMode.FIT_WIDTH -> {
                val scale = containerW / ratio
                PageSize(containerW, scale)
            }
            ZoomMode.FIT_HEIGHT -> {
                val scale = containerH
                PageSize(scale * ratio, containerH)
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
