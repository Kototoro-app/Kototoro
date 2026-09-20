package org.skepsun.kototoro.reader.core

import kotlin.math.roundToInt

/**
 * Paged-mode [SceneResourceWindowPlanner] strategy (improvement plan 2026-09 §8.1).
 *
 * Extracted verbatim from the paged host's hand-rolled request list, so behavior is
 * byte-for-byte identical (oracle: `SceneResourceWindowPlannerContractTest`):
 *
 * 1. Pages of the active slot — and of the COVER-transition pinned slot, when one is on
 *    screen — are IMMEDIATE / PRESENTATION_READY.
 * 2. Remaining pages visible in the frame are HIGH / PRESENTATION_READY.
 * 3. Fixed slot lookahead: [lookaheadSlots] slots behind the active slot are
 *    MEDIUM / SOURCE_READY, slots ahead are HIGH / SOURCE_READY. Pages already
 *    requested keep their earlier, stronger request.
 *
 * The active slot index is derived from the frame viewport along the scene's primary
 * axis the same way the host computed it (`round(offset / primaryExtent)` clamped to the
 * slot range), so the planner needs no host state beyond the request.
 */
class PagedSceneResourceWindowStrategy(
    private val lookaheadSlots: Int = 2,
) : SceneResourceWindowPlanner {

    override fun plan(request: SceneResourceWindowRequest): ReaderResourceWindow {
        val scene = checkNotNull(request.scene as? PagedReaderScene) {
            "PagedSceneResourceWindowStrategy requires a PagedReaderScene, " +
                "got ${request.scene::class.simpleName}"
        }
        val frame = request.frame
        val bounds = frame.viewport.bounds
        if (bounds.width <= 0f || bounds.height <= 0f) return ReaderResourceWindow(emptyList())

        val direction = scene.readingDirection
        val primaryExtent = if (direction.isHorizontal) bounds.width else bounds.height
        if (primaryExtent <= 0f) return ReaderResourceWindow(emptyList())

        val scrollOffset = if (direction.isHorizontal) bounds.left else bounds.top
        val activeSlotIndex = (scrollOffset / primaryExtent)
            .roundToInt()
            .coerceIn(0, (scene.slotCount - 1).coerceAtLeast(0))

        val requests = mutableListOf<PrefetchRequest>()

        // 1. Current slot (and transition-pinned slot) pages: IMMEDIATE presentation.
        scene.allSlots.getOrNull(activeSlotIndex)?.pageIds?.forEach { id ->
            requests.add(PrefetchRequest(id, PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY))
        }
        request.pinnedSlotIndex?.let { scene.allSlots.getOrNull(it) }?.pageIds?.forEach { id ->
            if (requests.none { it.pageId == id }) {
                requests.add(PrefetchRequest(id, PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY))
            }
        }

        // 2. Additional visible nodes in frame: HIGH presentation.
        frame.visibleNodes.forEach { node ->
            if (requests.none { it.pageId == node.pageId }) {
                requests.add(PrefetchRequest(node.pageId, PrefetchPriority.HIGH, PrefetchReadiness.PRESENTATION_READY))
            }
        }

        // 3. Lookahead slots: SOURCE_READY prefetch.
        for (step in 1..lookaheadSlots) {
            scene.allSlots.getOrNull(activeSlotIndex - step)?.pageIds?.forEach { id ->
                if (requests.none { it.pageId == id }) {
                    requests.add(PrefetchRequest(id, PrefetchPriority.MEDIUM, PrefetchReadiness.SOURCE_READY))
                }
            }
            scene.allSlots.getOrNull(activeSlotIndex + step)?.pageIds?.forEach { id ->
                if (requests.none { it.pageId == id }) {
                    requests.add(PrefetchRequest(id, PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY))
                }
            }
        }

        return ReaderResourceWindow(requests)
    }
}
