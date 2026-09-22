package org.skepsun.kototoro.reader.core

import kotlin.math.roundToInt

/**
 * Paged-mode [SceneResourceWindowPlanner] strategy (improvement plan 2026-09 §8.1).
 *
 * The default policy preserves the paged host's original resource window
 * (oracle: `SceneResourceWindowPlannerContractTest`):
 *
 * 1. Pages of the active slot — and of the COVER-transition pinned slot, when one is on
 *    screen — are IMMEDIATE / PRESENTATION_READY.
 * 2. Remaining pages visible in the frame are HIGH / PRESENTATION_READY.
 * 3. Fixed slot lookahead: [lookaheadSlots] slots behind the active slot are
 *    MEDIUM / SOURCE_READY, slots ahead are HIGH / SOURCE_READY. Pages already
 *    requested keep their earlier, stronger request.
 * 4. With [prepareAdjacentSlots], the immediately adjacent slots request PRESENTATION_READY
 *    even while hidden. Cover reveals its underlying page at the start of a drag, so waiting
 *    until it is visible to decode a cached source produces a placeholder flash. Keeping those
 *    neighbours drawable also prevents cancellation or reversal from discarding their images.
 *
 * The active slot index is derived from the frame viewport along the scene's primary
 * axis the same way the host computed it (`round(offset / primaryExtent)` clamped to the
 * slot range), so the planner needs no host state beyond the request.
 */
class PagedSceneResourceWindowStrategy(
    private val lookaheadSlots: Int = 2,
    private val prepareAdjacentSlots: Boolean = false,
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

        // 3. Only immediate neighbours need speculative presentation; farther slots stay source-only.
        for (step in 1..lookaheadSlots) {
            val readiness = if (prepareAdjacentSlots && step == 1) {
                PrefetchReadiness.PRESENTATION_READY
            } else {
                PrefetchReadiness.SOURCE_READY
            }
            scene.allSlots.getOrNull(activeSlotIndex - step)?.pageIds?.forEach { id ->
                if (requests.none { it.pageId == id }) {
                    requests.add(PrefetchRequest(id, PrefetchPriority.MEDIUM, readiness))
                }
            }
            scene.allSlots.getOrNull(activeSlotIndex + step)?.pageIds?.forEach { id ->
                if (requests.none { it.pageId == id }) {
                    requests.add(PrefetchRequest(id, PrefetchPriority.HIGH, readiness))
                }
            }
        }

        return ReaderResourceWindow(requests)
    }
}
