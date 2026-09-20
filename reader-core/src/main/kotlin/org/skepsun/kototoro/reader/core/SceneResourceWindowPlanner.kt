package org.skepsun.kototoro.reader.core

/**
 * Planner seam between reader scene state and the image pipeline's resource window
 * (improvement plan 2026-09 §8.1 / CS-9).
 *
 * Strategies own "what will be needed"; the ImagePipeline owns "how to load and budget"
 * (ADR 0002 Principle: ReaderCore owns semantics and prediction; ImagePipeline owns
 * execution and resource scheduling). Continuous and paged readers keep their own
 * strategies; the OUTPUT contract below is what unifies them.
 *
 * Output contract — every strategy MUST satisfy it (verified by
 * `SceneResourceWindowPlannerContractTest`):
 *
 * - **Complete snapshot**: a submitted window is the full desired resource set for that
 *   viewport snapshot. Pages absent from a newly submitted window become eligible for
 *   eviction, so a page must never be omitted on the assumption "the pipeline probably
 *   still has it". Cancelling/replacing an in-flight request for a page that dropped out
 *   of the window is the pipeline's scheduling decision, not the planner's.
 * - **Visible coverage**: every page in [ReaderFrame.visibleNodes] is requested exactly once.
 * - **Uniqueness**: pageIds are unique across the window (first request wins).
 * - **Priority/readiness coherence**: IMMEDIATE requests are PRESENTATION_READY;
 *   SOURCE_READY is only requested for pages not visible in the frame.
 * - **Mode opacity**: the window carries page identity, priority, readiness and predicted
 *   region only — never reading-mode or host knowledge. The pipeline must not need
 *   reading-mode branches to execute it.
 * - **Boundedness**: the request count grows with viewport span and lookahead, not with
 *   scene size.
 */
interface SceneResourceWindowPlanner {

    /**
     * Plans the desired resource window for one viewport snapshot.
     *
     * @return the window to submit, or null when the snapshot is equivalent to the
     * previously planned one and the caller should keep the existing window.
     */
    fun plan(request: SceneResourceWindowRequest): ReaderResourceWindow?
}

/**
 * One immutable snapshot of the state a [SceneResourceWindowPlanner] needs.
 *
 * @property scene Current scene (geometry revision included).
 * @property frame Frame resolved for the current viewport (visible nodes + progress).
 * @property motion Viewport motion telemetry for velocity-driven lookahead.
 * @property pinnedSlotIndex Slot pinned to the screen by a COVER-style transition, or null
 *   when no transition pin is active. Only meaningful to paged strategies; continuous
 *   strategies ignore it.
 */
data class SceneResourceWindowRequest(
    val scene: ReaderScene,
    val frame: ReaderFrame,
    val motion: ViewportMotion = ViewportMotion.Idle,
    val pinnedSlotIndex: Int? = null,
)
